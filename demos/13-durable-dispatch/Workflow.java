import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Durable intent -> HTTP delivery -> receipt -> complete local turn.
 * No LLM: the tool increments a journal-derived count. Faults are test-only CLI options.
 */
public final class Workflow {
    static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
        while ((n = stream.read(buffer)) != -1) {
            if (bytes.size() + n > 16000) throw new IOException("body too large");
            bytes.write(buffer, 0, n);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
    static void serve(Path store, boolean keyed) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/effect", exchange -> {
            int code = 200; String result;
            try {
                if (!exchange.getRequestMethod().equals("POST")) throw new IllegalArgumentException("POST required");
                String key = keyed ? exchange.getRequestHeaders().getFirst("Idempotency-Key") : UUID.randomUUID().toString();
                if (key == null) throw new IllegalArgumentException("key required");
                result = String.valueOf(Ledger.apply(store, key, read(exchange.getRequestBody()), "none").result);
            } catch (IllegalArgumentException ex) { code = 409; result = ex.getMessage(); }
              catch (Exception ex) { code = 500; result = ex.getClass().getSimpleName(); }
            byte[] body = result.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
            exchange.close();
        });
        server.start(); System.out.println("READY " + server.getAddress().getPort()); System.out.flush();
    }
    static String deliver(int port, Ledger.Entry intent) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/effect").openConnection();
        connection.setConnectTimeout(1500); connection.setReadTimeout(3000);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Idempotency-Key", intent.key);
        byte[] body = intent.body.getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            if (connection.getResponseCode() != 200) throw new IOException("receiver rejected delivery");
            try (InputStream in = connection.getInputStream()) { return read(in); }
        } finally { connection.disconnect(); }
    }
    static void resume(Path directory, int port, String fault) throws Exception {
        Files.createDirectories(directory);
        // Single dispatcher per directory. Separate from per-journal locks, held across delivery.
        try (RandomAccessFile owner = new RandomAccessFile(directory.resolve("dispatcher.lock").toFile(), "rw");
             java.nio.channels.FileLock lock = owner.getChannel().lock()) {
            for (Ledger.Entry intent : Ledger.read(directory.resolve("intents.wal"))) {
                String result = null;
                for (Ledger.Entry receipt : Ledger.read(directory.resolve("receipts.wal")))
                    if (receipt.key.equals(intent.key)) result = receipt.body;
                if (result == null) {
                    result = deliver(port, intent);
                    if (fault.equals("delivery")) Runtime.getRuntime().halt(23);
                    Ledger.apply(directory.resolve("receipts.wal"), intent.key, result, "none");
                }
                if (fault.equals("receipt")) Runtime.getRuntime().halt(23);
                // One record contains a complete fixed-material question/answer pair, not streaming fragments.
                Ledger.apply(directory.resolve("history.wal"), intent.key, intent.body + " => " + result, "none");
                if (fault.equals("history")) Runtime.getRuntime().halt(23);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "apply": System.out.println(Ledger.apply(Paths.get(args[1]), args[2], args[3], args[4]).result); break;
            case "count": System.out.println(Ledger.read(Paths.get(args[1])).size()); break;
            case "serve": serve(Paths.get(args[1]), Boolean.parseBoolean(args[2])); break;
            case "resume": resume(Paths.get(args[1]), Integer.parseInt(args[2]), args[3]); break;
            default: throw new IllegalArgumentException("unknown command");
        }
    }
}
