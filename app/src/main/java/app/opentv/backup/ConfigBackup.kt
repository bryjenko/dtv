/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.backup

import app.opentv.core.AppSettings
import app.opentv.data.db.SourceDao
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupPayload(
    val schema: Int = 1,
    val createdAt: Long,
    val preferences: Map<String, String>,
    val sources: List<SourceBackup>,
)

@Serializable
data class SourceBackup(
    val id: Long, val name: String, val kind: String, val url: String,
    val username: String? = null, val password: String? = null, val macAddress: String? = null,
    val epgUrl: String? = null, val userAgent: String, val liveFormat: String,
    val enabled: Boolean,
) {
    fun source() = Source(
        id = id, name = name, kind = SourceKind.valueOf(kind), url = url,
        username = username, password = password, macAddress = macAddress,
        epgUrl = epgUrl, userAgent = userAgent, liveFormat = LiveStreamFormat.valueOf(liveFormat),
        enabled = enabled,
    )

    companion object {
        fun from(source: Source) = SourceBackup(
            source.id, source.name, source.kind.name, source.url, source.username, source.password,
            source.macAddress, source.epgUrl, source.userAgent, source.liveFormat.name, source.enabled,
        )
    }
}

object ConfigBackup {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAGIC = "DTVB"
    private const val ITERATIONS = 600_000

    suspend fun create(settings: AppSettings, sources: SourceDao): ByteArray =
        json.encodeToString(
            BackupPayload(
                createdAt = System.currentTimeMillis(),
                preferences = settings.portableBackupValues(),
                sources = sources.all().map(SourceBackup::from),
            ),
        ).toByteArray(Charsets.UTF_8)

    fun inspect(plaintext: ByteArray): BackupPayload =
        json.decodeFromString<BackupPayload>(plaintext.toString(Charsets.UTF_8)).also {
        require(it.schema == 1) { "This backup was made by a newer D-TV version." }
        require(it.sources.size <= 100) { "The backup contains too many provider entries." }
        it.sources.forEach { source ->
            SourceKind.valueOf(source.kind)
            LiveStreamFormat.valueOf(source.liveFormat)
            require(source.name.length <= 256 && source.url.length <= 16_384)
        }
    }

    suspend fun restore(payload: BackupPayload, settings: AppSettings, sources: SourceDao) {
        sources.replaceAllForRestore(payload.sources.map { it.source() })
        settings.restorePortableBackup(payload.preferences)
    }

    fun encrypt(plain: ByteArray, code: String): ByteArray {
        require(code.matches(Regex("[0-9]{6}")))
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val key = deriveKey(code, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC.toByteArray(Charsets.US_ASCII))
        val ciphertext = cipher.doFinal(plain)
        return MAGIC.toByteArray(Charsets.US_ASCII) + byteArrayOf(1) + salt + iv + ciphertext
    }

    fun decrypt(encrypted: ByteArray, code: String): ByteArray {
        require(code.matches(Regex("[0-9]{6}")))
        require(encrypted.size >= 49 && encrypted.copyOfRange(0, 4).toString(Charsets.US_ASCII) == MAGIC) {
            "This file is not a D-TV backup."
        }
        require(encrypted[4].toInt() == 1) { "This backup uses an unsupported format." }
        val salt = encrypted.copyOfRange(5, 21)
        val iv = encrypted.copyOfRange(21, 33)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(code, salt), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC.toByteArray(Charsets.US_ASCII))
        return cipher.doFinal(encrypted.copyOfRange(33, encrypted.size))
    }

    private fun deriveKey(code: String, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val blockInput = salt + byteArrayOf(0, 0, 0, 1)
        var u = mac.doFinal(blockInput)
        val result = u.copyOf()
        repeat(ITERATIONS - 1) {
            u = mac.doFinal(u)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return result
    }
}

/** Google Drive's hidden appDataFolder; the uploaded file contains ciphertext only. */
class DriveBackupStore(private val client: OkHttpClient = OkHttpClient()) {
    private val base = "https://www.googleapis.com/drive/v3/files"
    private val upload = "https://www.googleapis.com/upload/drive/v3/files"

    fun upload(token: String, encrypted: ByteArray) {
        val existing = findLatest(token)
        val request = if (existing == null) {
            val metadata = """{"name":"dtv-config.enc","parents":["appDataFolder"]}"""
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addFormDataPart("metadata", null, metadata.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addFormDataPart("file", "dtv-config.enc", encrypted.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            Request.Builder().url("$upload?uploadType=multipart").header("Authorization", "Bearer $token").post(body).build()
        } else {
            Request.Builder().url("$upload/$existing?uploadType=media")
                .header("Authorization", "Bearer $token")
                .patch(encrypted.toRequestBody("application/octet-stream".toMediaType())).build()
        }
        execute(request)
    }

    fun download(token: String): ByteArray {
        val id = findLatest(token) ?: error("No D-TV backup was found in this Google account.")
        return execute(Request.Builder().url("$base/$id?alt=media").header("Authorization", "Bearer $token").get().build())
    }

    private fun findLatest(token: String): String? {
        val q = "name = 'dtv-config.enc' and 'appDataFolder' in parents and trashed = false"
        val encoded = java.net.URLEncoder.encode(q, "UTF-8")
        val body = execute(Request.Builder().url("$base?q=$encoded&orderBy=modifiedTime%20desc&pageSize=1&fields=files(id)")
            .header("Authorization", "Bearer $token").get().build()).toString(Charsets.UTF_8)
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    }

    private fun execute(request: Request): ByteArray = client.newCall(request).execute().use { response ->
        val body = response.body?.bytes() ?: ByteArray(0)
        if (!response.isSuccessful) error("Google Drive request failed (${response.code}). Check your connection and Google account access.")
        body
    }
}
