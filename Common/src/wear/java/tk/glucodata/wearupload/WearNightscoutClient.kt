package tk.glucodata.wearupload

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import tk.glucodata.NightscoutTokenGrant

/** HTTPS-only V3 transport with a watch-local subject token and explicit acknowledgements. */
internal class WearNightscoutClient(
    private val address: URI,
    private val subjectToken: String,
    private val active: () -> Boolean,
) {
    private var bearer: String? = null

    fun send(identifier: String, payload: String): Int {
        if (bearer == null) {
            val status = authorize()
            if (status != 200) return status
        }
        val first = post(identifier, payload)
        if (first != 401) return first
        bearer = null
        val status = authorize()
        return if (status == 200) post(identifier, payload) else status
    }

    private fun authorize(): Int {
        val token = URLEncoder.encode(subjectToken, Charsets.UTF_8.name())
        return request("/api/v2/authorization/request/$token", "GET") { conn ->
            val status = conn.responseCode
            if (status == 200) {
                val grant = NightscoutTokenGrant.parse(response(conn))
                val token = grant?.token?.takeIf { it.length <= MAX_RESPONSE && '\r' !in it && '\n' !in it }
                    ?: throw IOException("Nightscout did not supply an access token")
                bearer = token
            } else if (status in 201..299) {
                throw IOException("Unexpected Nightscout authorization response")
            }
            status
        }
    }

    private fun post(identifier: String, payload: String): Int =
        request("/api/v3/entries", "POST") { conn ->
            val bytes = payload.toByteArray(Charsets.UTF_8)
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${checkNotNull(bearer)}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            if (status == 200 || status == 201) {
                if (JSONObject(response(conn)).optString("identifier") != identifier) {
                    throw IOException("Nightscout did not acknowledge the reading identifier")
                }
            }
            status
        }

    private fun <T> request(path: String, method: String, block: (HttpsURLConnection) -> T): T {
        if (!active()) throw InterruptedIOException("Nightscout upload stopped")
        val conn = address.resolve(path).toURL().openConnection() as HttpsURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.requestMethod = method
        conn.setRequestProperty("Accept", "application/json")
        try {
            return block(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun response(conn: HttpsURLConnection): String = conn.inputStream.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_RESPONSE) throw IOException("Nightscout response too large")
            out.write(buffer, 0, count)
        }
        out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val MAX_RESPONSE = 16_384
    }
}
