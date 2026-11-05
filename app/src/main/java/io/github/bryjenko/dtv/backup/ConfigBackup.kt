/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.backup

import io.github.bryjenko.dtv.core.AppSettings
import io.github.bryjenko.dtv.data.db.SourceDao
import io.github.bryjenko.dtv.data.model.LiveStreamFormat
import io.github.bryjenko.dtv.data.model.ChannelPreference
import io.github.bryjenko.dtv.data.model.Source
import io.github.bryjenko.dtv.data.model.SourceKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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
    val channelPreferences: List<ChannelPreference> = emptyList(),
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

    suspend fun create(settings: AppSettings, sources: SourceDao, channels: io.github.bryjenko.dtv.data.db.ChannelDao): ByteArray =
        json.encodeToString(
            BackupPayload(
                createdAt = System.currentTimeMillis(),
                preferences = settings.portableBackupValues(),
                sources = sources.all().map(SourceBackup::from),
                channelPreferences = channels.allForBackup().map {
                    ChannelPreference(it.sourceId, it.streamId, it.favourite, it.hidden, it.sortIndex, it.customName)
                },
            ),
        ).toByteArray(Charsets.UTF_8)

    fun inspect(plaintext: ByteArray): BackupPayload =
        json.decodeFromString<BackupPayload>(plaintext.toString(Charsets.UTF_8)).also {
        require(it.schema == 1) { "This backup was made by a newer D-TV version." }
        require(it.sources.size <= 100) { "The backup contains too many provider entries." }
        require(it.channelPreferences.size <= 250_000) { "The backup contains too many channel preferences." }
        it.sources.forEach { source ->
            SourceKind.valueOf(source.kind)
            LiveStreamFormat.valueOf(source.liveFormat)
            require(source.name.length <= 256 && source.url.length <= 16_384)
        }
    }

    suspend fun restore(payload: BackupPayload, settings: AppSettings, sources: SourceDao) {
        sources.replaceAllForRestore(payload.sources.map { it.source() })
        settings.restorePortableBackup(payload.preferences)
        settings.setPendingChannelPreferences(payload.channelPreferences)
    }

    fun encrypt(plain: ByteArray, code: String): ByteArray {
        require(code.matches(Regex("[0-9]{6}")))
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val key = deriveKey(code, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC.toByteArray(Charsets.US_ASCII))
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(plain) }
            output.toByteArray()
        }
        val ciphertext = cipher.doFinal(compressed)
        return MAGIC.toByteArray(Charsets.US_ASCII) + byteArrayOf(2) + salt + iv + ciphertext
    }

    fun decrypt(encrypted: ByteArray, code: String): ByteArray {
        require(code.matches(Regex("[0-9]{6}")))
        require(encrypted.size >= 49 && encrypted.copyOfRange(0, 4).toString(Charsets.US_ASCII) == MAGIC) {
            "This file is not a D-TV backup."
        }
        val format = encrypted[4].toInt()
        require(format == 1 || format == 2) { "This backup uses an unsupported format." }
        val salt = encrypted.copyOfRange(5, 21)
        val iv = encrypted.copyOfRange(21, 33)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(code, salt), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC.toByteArray(Charsets.US_ASCII))
        val plaintext = cipher.doFinal(encrypted.copyOfRange(33, encrypted.size))
        if (format == 1) return plaintext
        return GZIPInputStream(ByteArrayInputStream(plaintext)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_DECOMPRESSED_BYTES) { "This backup is too large to restore." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
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

    private const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
}
