package com.hohoo.ailab.structured;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** No retries: a read timeout does not tell us whether the provider processed the request. */
public final class ChatClient {
    private final URL endpoint;
    private final String key;
    private final int readTimeout;
    public ChatClient(String endpoint, String key, int readTimeout, boolean localTest) throws IOException {
        this.endpoint = new URL(endpoint);
        if (!"https".equals(this.endpoint.getProtocol())
                && !(localTest && "127.0.0.1".equals(this.endpoint.getHost())))
            throw new IOException("https_required");
        if (this.endpoint.getUserInfo() != null || this.endpoint.getQuery() != null)
            throw new IOException("credentials_or_query_not_allowed_in_endpoint");
        this.key = key;
        this.readTimeout = readTimeout;
    }

    public JsonObject post(JsonObject request) throws IOException {
        HttpURLConnection c = (HttpURLConnection) endpoint.openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(readTimeout);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("Authorization", "Bearer " + key);
            byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("http_" + status);
            ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytesOut.size() + n > 1024 * 1024) throw new IOException("response_too_large");
                    bytesOut.write(buffer, 0, n);
                }
            }
            try {
                return JsonParser.parseString(new String(bytesOut.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) { throw new IOException("invalid_response_json"); }
        } finally { c.disconnect(); }
    }
}
