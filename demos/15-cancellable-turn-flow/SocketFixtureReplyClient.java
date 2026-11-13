package com.hohoo.ailab.bounded;

import com.hohoo.ailab.stream.StreamReader;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Deliberately narrow loopback fixture: HTTP/1.1, close framing, SSE. Not a provider SDK. */
public final class SocketFixtureReplyClient implements TurnFlow.ReplySource {
    interface Phase { void reached(String phase, Socket socket) throws IOException; }
    private final int port;
    private final CancellationLease lease;
    private final Consumer<String> preview;
    private final Phase phase;

    public SocketFixtureReplyClient(int port, CancellationLease lease, Consumer<String> preview) {
        this(port, lease, preview, (name, socket) -> {});
    }
    // Controlled race gates only; the public adapter has no test gates.
    SocketFixtureReplyClient(int port, CancellationLease lease, Consumer<String> preview, Phase phase) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("fixture port");
        this.port = port; this.lease = lease; this.preview = preview; this.phase = phase;
    }

    static void responseHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {13, 10, 13, 10};
        for (int n = 0; n < 8192; n++) {
            int b = input.read();
            if (b < 0) throw new EOFException("incomplete fixture headers");
            if (b > 127) throw new IOException("non-ASCII fixture headers");
            bytes.write(b);
            matched = b == end[matched] ? matched + 1 : (b == 13 ? 1 : 0);
            if (matched == 4) {
                String header = new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
                String lower = header.toLowerCase(java.util.Locale.ROOT);
                if (!header.startsWith("HTTP/1.1 200 OK\r\n")
                        || !lower.contains("\r\ncontent-type: text/event-stream\r\n")
                        || !lower.contains("\r\nconnection: close\r\n")
                        || lower.contains("\r\ntransfer-encoding:") || lower.contains("\r\ncontent-length:"))
                    throw new IOException("unsupported fixture HTTP framing");
                return;
            }
        }
        throw new IOException("fixture headers too large");
    }

    public String generateOnce(byte[] request, int remainingMs) throws IOException {
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            phase.reached("before-register", socket);
            if (!lease.register(socket)) throw new InterruptedIOException("cancelled before resource registration");
            phase.reached("registered", socket);
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), remainingMs);
            socket.setSoTimeout(remainingMs);
            OutputStream output = socket.getOutputStream();
            output.write(("POST /fixture HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\nContent-Length: "
                    + request.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(request); output.flush();
            InputStream input = socket.getInputStream();
            responseHeaders(input);
            String answer = new StreamReader().read(input, preview);
            phase.reached("reply-complete", socket);
            return answer;
        } finally {
            lease.release(socket);
        }
    }
}
