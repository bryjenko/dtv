package io.github.bryjenko.dtv.backup

import android.util.Log
import io.github.bryjenko.dtv.core.AppSettings
import io.github.bryjenko.dtv.data.db.ChannelDao
import io.github.bryjenko.dtv.data.db.SourceDao
import io.github.bryjenko.dtv.pairing.PairingHttp
import java.io.BufferedReader
import java.io.InputStreamReader
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
        val PAGE = """<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer"><title>D-TV Backup</title>
<style>body{font:17px system-ui;max-width:520px;margin:30px auto;padding:0 18px;background:#101522;color:#fff}button,input,textarea{font:inherit;padding:13px;margin:8px 0;width:100%;box-sizing:border-box;border-radius:10px}button{background:#168cff;color:white;border:0}input,textarea{background:#202a3b;color:white;border:1px solid #52627b}section{display:none}section.on{display:block}.muted{color:#b4c2d6}textarea{overflow-wrap:anywhere}</style></head>
<body><h1>D-TV Backup &amp; Restore</h1><p class="muted">Keep this phone and both TVs on the same Wi-Fi network.</p>
<button onclick="show('backup')">Backup</button><button onclick="show('restore')">Restore</button>
<section id="backup"><h2>Create backup</h2><p>Create a six digit code. You will enter it on the new TV.</p><input id="pin1" inputmode="numeric" maxlength="6" placeholder="6 digit code"><input id="pin2" inputmode="numeric" maxlength="6" placeholder="Confirm code"><button id="makeBackup" onclick="backup()">Create backup</button></section>
<section id="backup-result"><h2>Backup text</h2><p id="copyHelp">Your encrypted backup is ready. Copy this text to your phone clipboard.</p><textarea id="backupText" rows="8" readonly></textarea><button onclick="copyBackup()">Copy backup text</button></section>
<section id="restore"><h2>Restore backup</h2><p>Paste the saved backup text, then enter its six digit code.</p><textarea id="restoreText" rows="8" placeholder="Paste your backup text here"></textarea><button onclick="pasteBackup()">Paste from clipboard</button><input id="restorePin" inputmode="numeric" maxlength="6" placeholder="6 digit code"><button onclick="restoreBackup()">Restore to this TV</button></section>
<p id="message" role="status"></p>
<script>
const token=new URLSearchParams(location.search).get('t');
const el=id=>document.getElementById(id);
function show(id){document.querySelectorAll('section').forEach(s=>s.classList.remove('on'));el(id).classList.add('on')}
async function post(path,values){const response=await fetch(path+'?t='+encodeURIComponent(token),{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(values)});const text=await response.text();if(!response.ok)throw Error(text);return text}
async function backup(){const one=el('pin1').value,two=el('pin2').value;if(!/^\d{6}$/.test(one)||one!==two){el('message').textContent='Enter and confirm the same six digit code.';return}el('makeBackup').disabled=true;el('message').textContent='Creating encrypted backup…';try{el('backupText').value=await post('/backup',{pin:one});show('backup-result');try{await navigator.clipboard.writeText(el('backupText').value);el('message').textContent='Copied to clipboard. On the new TV, scan its QR code and choose Restore.'}catch(error){el('message').textContent='Backup created. Tap “Copy backup text” below to copy it.';el('backupText').focus();el('backupText').select()}}catch(error){el('message').textContent='Backup failed: '+error.message}finally{el('makeBackup').disabled=false}}
async function copyBackup(){const box=el('backupText');box.focus();box.select();try{if(navigator.clipboard&&navigator.clipboard.writeText){await navigator.clipboard.writeText(box.value);el('message').textContent='Copied to clipboard. On the new TV, scan its QR code and choose Restore.';return}}catch(error){}try{if(document.execCommand('copy')){el('message').textContent='Copied to clipboard. On the new TV, scan its QR code and choose Restore.';return}}catch(error){}box.focus();box.select();el('message').textContent='Backup text selected. Use your phone’s Copy command, then paste it on the new TV.'}
async function pasteBackup(){const box=el('restoreText');try{if(navigator.clipboard&&navigator.clipboard.readText){box.value=await navigator.clipboard.readText();el('message').textContent='Backup pasted.';return}}catch(error){}box.focus();el('message').textContent='Tap and hold in the text box, then choose Paste from your phone’s menu.'}
async function restoreBackup(){const code=el('restorePin').value,text=el('restoreText').value.trim();if(!/^\d{6}$/.test(code)){el('message').textContent='Enter all six digits.';return}if(!text){el('message').textContent='Paste the backup text first.';return}el('message').textContent='Restoring…';try{el('message').textContent=await post('/restore',{pin:code,data:text})}catch(error){el('message').textContent=error.message}}
</script></body></html>"""
    }
}
