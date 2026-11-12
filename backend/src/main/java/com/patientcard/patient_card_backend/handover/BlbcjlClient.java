package com.patientcard.patient_card_backend.handover;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only hospital interface. Never log keys, identifiers, body or remote responses. */
@Component
public class BlbcjlClient {
    @Value("${CLINICAL_NOTES_BLBCJL_ENDPOINT:}") private String endpoint;
    @Value("${CLINICAL_NOTES_BLBCJL_ACCESS_KEY:}") private String accessKey;
    @Value("${CLINICAL_NOTES_BLBCJL_MBKMC:}") private String mbkmc;
    @Value("${CLINICAL_NOTES_BLBCJL_WJJGMC:}") private String wjjgmc;
    @Value("${CLINICAL_NOTES_BLBCJL_CONNECT_TIMEOUT_MS:5000}") private int connectTimeoutMs;
    @Value("${CLINICAL_NOTES_BLBCJL_READ_TIMEOUT_MS:15000}") private int readTimeoutMs;
    @Value("${CLINICAL_NOTES_BLBCJL_MAX_RESPONSE_BYTES:2097152}") private int maxResponseBytes;
    private final ObjectMapper json;
    public BlbcjlClient(ObjectMapper json) { this.json = json; }
    public byte[] fetch(String mrn, String bqdm) {
        if (endpoint == null || endpoint.isBlank() || accessKey == null || accessKey.isBlank()
                || mrn == null || mrn.isBlank() || bqdm == null) {
            throw new IllegalStateException("病程接口尚未配置完整");
        }
        HttpURLConnection c = null;
        try {
            URI uri = URI.create(endpoint);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            c = (HttpURLConnection) uri.toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(Math.max(100, Math.min(30000, connectTimeoutMs)));
            c.setReadTimeout(Math.max(100, Math.min(60000, readTimeoutMs)));
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("Accept", "application/json");
            Map<String,Object> data = new LinkedHashMap<>();
            data.put("BQDM", bqdm); data.put("BLH", mrn); data.put("MBKMC", mbkmc); data.put("WJJGMC", wjjgmc);
            Map<String,Object> request = new LinkedHashMap<>();
            request.put("accesskey", accessKey); request.put("method", "BLBCJL"); request.put("data", data);
            byte[] payload = json.writeValueAsBytes(request);
            c.setFixedLengthStreamingMode(payload.length);
            try (var out = c.getOutputStream()) { out.write(payload); }
            if (c.getResponseCode() != 200) throw new IllegalStateException();
            String type = java.util.Objects.toString(c.getContentType(), "").toLowerCase(Locale.ROOT);
            String media = type.split(";", 2)[0].trim();
            if (!media.equals("application/json") && !media.endsWith("+json")) throw new IllegalStateException();
            if (type.contains("charset=") && !type.matches(".*charset=\\s*[\"']?utf-8[\"']?\\s*(;.*)?")) throw new IllegalStateException();
            int limit = Math.max(1024, Math.min(20 * 1024 * 1024, maxResponseBytes));
            if (c.getContentLengthLong() > limit) throw new IllegalStateException();
            try (InputStream in = c.getInputStream()) {
                byte[] result = in.readNBytes(limit + 1);
                if (result.length > limit) throw new IllegalStateException();
                // Reject invalid UTF-8 before Jackson is invoked.
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(result));
                return result;
            }
        } catch (Exception ignored) {
            throw new IllegalStateException("病程接口读取失败，请检查院内接口配置和网络");
        } finally { if (c != null) c.disconnect(); }
    }
}
