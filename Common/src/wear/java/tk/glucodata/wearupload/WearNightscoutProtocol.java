package tk.glucodata.wearupload;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import javax.net.ssl.HttpsURLConnection;

/** Nightscout API v3 transport. The same reading always has the same identifier. */
public final class WearNightscoutProtocol {
    private static final int TIMEOUT_MS = 8_000;
    private static final int MAX_RESPONSE_BYTES = 16_384;
    private final URI base;
    private final String subjectToken;
    private final BooleanSupplier active;
    private String bearer;

    public WearNightscoutProtocol(String url, String token) throws URISyntaxException {
        this(url, token, () -> true);
    }

    public WearNightscoutProtocol(String url, String token, BooleanSupplier active) throws URISyntaxException {
        base = endpoint(url);
        subjectToken = token;
        this.active = active;
    }

    public static URI endpoint(String url) throws URISyntaxException {
        URI uri = new URI(url.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || (!uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) {
            throw new URISyntaxException("", "Supply an HTTPS server address without a path");
        }
        return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(),
                "/", null, null);
    }

    public static String device(String sensor) {
        byte[] hash = digest("SHA-256").digest(sensor.getBytes(StandardCharsets.UTF_8));
        StringBuilder device = new StringBuilder("JugglucoNG-Wear-");
        for (int i = 0; i < 12; i++) device.append(String.format(Locale.ROOT, "%02x", hash[i] & 0xff));
        return device.toString();
    }

    /** Matches the published Nightscout API v3 device/date identifier (UUIDv5). */
    public static String readingId(String device, long timeMs) {
        MessageDigest sha = digest("SHA-1");
        sha.update("NightscoutRocks!".getBytes(StandardCharsets.US_ASCII));
        byte[] hash = sha.digest((device + "_" + timeMs).getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | (5 << 4));
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer bytes = ByteBuffer.wrap(hash);
        return new UUID(bytes.getLong(), bytes.getLong()).toString();
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public int send(String device, long timeMs, int mgdl) throws IOException, JSONException {
        return send(device, timeMs, mgdl, null);
    }

    public int send(String device, long timeMs, int mgdl, String direction) throws IOException, JSONException {
        if (bearer == null) {
            int code = authorize();
            if (code != 200) return code;
        }
        int code = post(device, timeMs, mgdl, direction);
        if (code == 401) {
            bearer = null;
            code = authorize();
            if (code == 200) code = post(device, timeMs, mgdl, direction);
        }
        return code;
    }

    private int authorize() throws IOException, JSONException {
        String path = "/api/v2/authorization/request/"
                + URLEncoder.encode(subjectToken, StandardCharsets.UTF_8.name());
        HttpsURLConnection conn = connect(path, "GET");
        try {
            int status = conn.getResponseCode();
            if (status >= 200 && status < 300 && status != 200) {
                throw new IOException("Unexpected authorization response");
            }
            if (status != 200) return status;
            try (InputStream stream = conn.getInputStream()) {
                JSONObject result = readJson(stream);
                bearer = result.optString("token", "").trim();
                if (bearer.isEmpty() || bearer.contains("\r") || bearer.contains("\n")) {
                    bearer = null;
                    throw new IOException("Nightscout did not supply a valid access session");
                }
            }
            return status;
        } finally {
            conn.disconnect();
        }
    }

    private int post(String device, long timeMs, int mgdl, String direction) throws IOException, JSONException {
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        date.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject reading = new JSONObject()
                .put("app", "JugglucoNG-WearPilot").put("type", "sgv").put("sgv", mgdl)
                .put("date", timeMs).put("dateString", date.format(new Date(timeMs))).put("utcOffset", 0)
                .put("device", device);
        if (direction != null) reading.put("direction", direction);
        byte[] body = reading.toString().getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection conn = connect("/api/v3/entries", "POST");
        try {
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + bearer);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream stream = conn.getOutputStream()) {
                stream.write(body);
            }
            int status = conn.getResponseCode();
            if (status == 200 || status == 201) {
                try (InputStream stream = conn.getInputStream()) {
                    JSONObject result = readJson(stream);
                    if (!readingId(device, timeMs).equals(result.optString("identifier"))) {
                        throw new IOException("Nightscout did not acknowledge the reading identifier");
                    }
                }
            }
            return status;
        } finally {
            conn.disconnect();
        }
    }

    private HttpsURLConnection connect(String path, String method) throws IOException {
        if (!active.getAsBoolean()) throw new InterruptedIOException("Upload session stopped");
        HttpsURLConnection conn = (HttpsURLConnection) base.resolve(path).toURL().openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false);
        conn.setUseCaches(false);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static JSONObject readJson(InputStream input) throws IOException, JSONException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (out.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Response is too large");
            out.write(buffer, 0, count);
        }
        return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }
}
