package tk.glucodata.wearupload

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal class WearUploadConfig(
    val url: String,
    val subjectToken: String,
    val sinceMs: Long,
    val session: String,
) {
    class InvalidAddress : IllegalArgumentException()
    class InvalidToken : IllegalArgumentException()

    companion object {
        private const val KEY = "wear-nightscout-credentials-v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private val validToken = Regex("[A-Za-z0-9_.~-]{6,256}")

        private fun file(context: Context) =
            AtomicFile(File(context.noBackupFilesDir, "wear-nightscout-config"))

        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun encryptionKey(): SecretKey {
            val existing = keyStore().getKey(KEY, null) as? SecretKey
            if (existing != null) return existing
            return KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }

        fun address(url: String): String = runCatching {
            require(url.length <= 512)
            val uri = URI(url.trim())
            require(uri.scheme.equals("https", true) && uri.host != null &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.port != 0 && uri.port <= 65_535 &&
                (uri.rawPath.isEmpty() || uri.rawPath == "/"))
            URI("https", null, uri.host.lowercase(Locale.ROOT), if (uri.port == 443) -1 else uri.port,
                "/", null, null).toASCIIString()
        }.getOrElse { throw InvalidAddress() }

        fun read(context: Context): WearUploadConfig? {
            val storage = file(context)
            val bytes = try {
                storage.readFully()
            } catch (missing: FileNotFoundException) {
                if (storage.baseFile.exists()) throw missing
                return null
            }
            if (bytes.isEmpty()) return null
            require(bytes.size in (IV_LENGTH + 16)..8192)
            val key = keyStore().getKey(KEY, null) as? SecretKey ?: error("Missing local Nightscout key")
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            val json = JSONObject(String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8))
            return WearUploadConfig(
                address(json.getString("url")), json.getString("token"), json.getLong("since"), json.getString("session"),
            )
        }

        fun save(context: Context, url: String, token: String, current: WearUploadConfig?): WearUploadConfig {
            val previous = current?.takeIf { it.url == url }
            val subjectToken = token.trim().ifEmpty { previous?.subjectToken.orEmpty() }
            if (!subjectToken.matches(validToken)) throw InvalidToken()
            if (previous != null && previous.subjectToken == subjectToken) return previous
            val config = WearUploadConfig(
                url, subjectToken, previous?.sinceMs ?: System.currentTimeMillis(),
                previous?.session ?: UUID.randomUUID().toString(),
            )
            val json = JSONObject().put("url", config.url).put("token", config.subjectToken)
                .put("since", config.sinceMs).put("session", config.session).toString()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            check(cipher.iv.size == IV_LENGTH)
            write(context, cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8)))
            return config
        }

        fun stop(context: Context) = write(context, ByteArray(0))

        private fun write(context: Context, bytes: ByteArray) {
            val storage = file(context)
            val stream = storage.startWrite()
            try {
                stream.write(bytes)
                storage.finishWrite(stream)
            } catch (error: Exception) {
                storage.failWrite(stream)
                throw error
            }
        }
    }
}
