package io.github.bryjenko.dtv.backup

import android.util.Log
import io.github.bryjenko.dtv.core.AppSettings
import io.github.bryjenko.dtv.data.db.ChannelDao
import io.github.bryjenko.dtv.data.db.SourceDao
import io.github.bryjenko.dtv.pairing.PairingHttp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Temporary, LAN-only encrypted backup bridge. No configuration data leaves the home network. */
class BackupTransferServer(
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val sources: SourceDao,
    private val channels: ChannelDao,
) {
    sealed interface State {
        data object Idle : State
        data class Listening(val url: String) : State
        data class Complete(val message: String) : State
        data class Failed(val message: String) : State
    }
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state = mutableState.asStateFlow()
    private var socket: ServerSocket? = null
    private var token = ""
    private var restoreAttempts = 0
    @Volatile private var running = false

    fun start() {
        if (running) return
        val host = lanAddress() ?: run { mutableState.value = State.Failed("Connect this TV and phone to the same Wi-Fi network."); return }
        token = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val server = runCatching { ServerSocket(0) }.getOrElse { mutableState.value = State.Failed("Could not start the local transfer."); return }
        socket = server; running = true
        mutableState.value = State.Listening("http://$host:${server.localPort}/?t=$token")
        thread(name = "dtv-backup-transfer", isDaemon = true) {
            while (running) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { handle(client) }.onFailure { Log.w("DtvBackup", "Transfer request failed", it) }
                runCatching { client.close() }
            }
        }
        scope.launch { delay(5 * 60 * 1000L); if (running) { stop(); mutableState.value = State.Failed("Transfer expired. Start again when ready.") } }
    }

    fun stop() { running = false; runCatching { socket?.close() }; socket = null; token = "" }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) return
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val request = reader.readLine()?.let(PairingHttp::parseRequestLine) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; PairingHttp.parseHeader(line)?.let { headers[it.first] = it.second } }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) { client.getOutputStream().write(PairingHttp.httpResponse("413 Payload Too Large", "text/plain", "Transfer too large.")); return }
        val body = CharArray(length); var count = 0
        while (count < length) { val n = reader.read(body, count, length-count); if (n < 0) break; count += n }
        val params = PairingHttp.parseFormEncoded(String(body, 0, count))
        val supplied = request.third["t"] ?: params["t"].orEmpty()
        if (!PairingHttp.secretsMatch(supplied, token)) { client.getOutputStream().write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found.")); return }
        val out = client.getOutputStream()
        if (request.first == "GET") { out.write(PairingHttp.httpResponse("200 OK", "text/html", PAGE)); return }
        if (request.first != "POST") { out.write(PairingHttp.httpResponse("405 Method Not Allowed", "text/plain", "Method not allowed.")); return }
        when (request.second) {
            "/backup" -> {
                val pin = params["pin"].orEmpty()
                if (!pin.matches(Regex("[0-9]{6}"))) { out.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Enter six digits.")); return }
                val encrypted = runBlocking(Dispatchers.IO) { ConfigBackup.encrypt(ConfigBackup.create(settings, sources, channels), pin) }
                out.write(PairingHttp.httpResponse("200 OK", "text/plain", android.util.Base64.encodeToString(encrypted, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)))
            }
            "/restore" -> {
                try {
                    val pin = params["pin"].orEmpty()
                    val bytes = android.util.Base64.decode(params["data"].orEmpty(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    val payload = ConfigBackup.inspect(ConfigBackup.decrypt(bytes, pin))
                    runBlocking { ConfigBackup.restore(payload, settings, sources) }
                    out.write(PairingHttp.httpResponse("200 OK", "text/plain", "Restore complete. Return to D-TV to apply the configuration."))
                    mutableState.value = State.Complete("Restore complete. The TV has accepted your encrypted backup.")
                    stop()
                } catch (e: Exception) {
                    restoreAttempts++
                    out.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Could not unlock this backup. Check the code and pasted text."))
                    if (restoreAttempts >= 5) {
                        mutableState.value = State.Failed("Too many restore attempts. Start a new transfer on the TV.")
                        stop()
                    }
                }
            }
            else -> out.write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."))
        }
    }

    private fun lanAddress(): String? = runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses).asSequence() }.filter { !it.isLoopbackAddress && !it.hostAddress.orEmpty().contains(':') }
            .filter { PairingHttp.isPrivateAddress(it.hostAddress) }.sortedBy { it.hostAddress?.startsWith("169.254") == true }
            .firstOrNull()?.hostAddress
    }.getOrNull()

    private companion object {
        const val MAX_BODY = 32 * 1024 * 1024
        val PAGE = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer"><title>D-TV Backup</title><style>body{font:17px system-ui;max-width:520px;margin:30px auto;padding:0 18px;background:#101522;color:#fff}button,input,textarea{font:inherit;padding:13px;margin:8px 0;width:100%;box-sizing:border-box;border-radius:10px}button{background:#168cff;color:white;border:0}input,textarea{background:#202a3b;color:white;border:1px solid #52627b}section{display:none}section.on{display:block}.muted{color:#b4c2d6}</style><h1>D-TV Backup &amp; Restore</h1><p class="muted">Keep this phone and both TVs on the same Wi-Fi network.</p><button onclick="show('backup')">Backup</button><button onclick="show('restore')">Restore</button><section id="backup"><h2>Create backup</h2><p>Create a six digit code. You will enter it on the new TV.</p><input id="p1" inputmode="numeric" maxlength="6" placeholder="6 digit code"><input id="p2" inputmode="numeric" maxlength="6" placeholder="Confirm code"><button onclick="backup()">Create and copy backup</button></section><section id="restore"><h2>Restore backup</h2><textarea id="data" rows="7" placeholder="Paste your backup text here"></textarea><button onclick="paste()">Paste from clipboard</button><input id="pin" inputmode="numeric" maxlength="6" placeholder="6 digit code"><button onclick="restore()">Restore to this TV</button></section><p id="msg"></p><script>const t=new URLSearchParams(location.search).get('t');function show(s){document.querySelectorAll('section').forEach(x=>x.className='');document.getElementById(s).className='on'}async function post(path,obj){let r=await fetch(path+'?t='+encodeURIComponent(t),{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(obj)});let x=await r.text();if(!r.ok)throw Error(x);return x}async function backup(){let a=p1.value,b=p2.value;if(!/^\\d{6}$/.test(a)||a!==b){msg.textContent='Enter and confirm the same six digits.';return}try{let x=await post('/backup',{pin:a});data.value=x;try{await navigator.clipboard.writeText(x);msg.textContent='Encrypted backup copied to clipboard. Open the new TV transfer page and choose Restore.'}catch(e){show('restore');msg.textContent='Backup created. Select all text and copy it to your clipboard.'}}catch(e){msg.textContent='Backup failed: '+e.message}}async function paste(){try{data.value=await navigator.clipboard.readText();msg.textContent='Backup pasted.'}catch(e){msg.textContent='Clipboard access is unavailable here. Tap the box and choose Paste.'}}async function restore(){if(!/^\\d{6}$/.test(pin.value)){msg.textContent='Enter all six digits.';return}try{msg.textContent=await post('/restore',{pin:pin.value,data:data.value.trim()})}catch(e){msg.textContent=e.message}}</script>"""
    }
}
