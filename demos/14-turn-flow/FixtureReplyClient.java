package com.hohoo.ailab.bounded;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Local test transport only. No key or public-network provider support. */
public final class FixtureReplyClient implements TurnFlow.ReplySource {
    private final URL endpoint;
    public FixtureReplyClient(URL endpoint) {
        if (!"http".equals(endpoint.getProtocol()) || !"127.0.0.1".equals(endpoint.getHost()))
            throw new IllegalArgumentException("only a loopback HTTP fixture is allowed");
        this.endpoint = endpoint;
    }
    public String generateOnce(byte[] request, int remainingMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)endpoint.openConnection(Proxy.NO_PROXY);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(remainingMs);
            connection.setReadTimeout(remainingMs);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            // Streaming mode prevents the JDK's buffered POST replay on an I/O failure.
            connection.setFixedLengthStreamingMode(request.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(request); }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("fixture HTTP " + status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                int b;
                while ((b = input.read()) != -1) {
                    if (bytes.size() == 4096) throw new IOException("fixture response exceeds 4096 bytes");
                    bytes.write(b);
                }
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } finally {
            connection.disconnect();
        }
    }
}
