import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Loopback teaching server. X-Test-* headers intentionally inject failures. */
public final class IdempotencyServer {
    private static final int CAPACITY = 64, MAX_BODY = 4096;
    private final Map<String, Record> records = new HashMap<String, Record>();
    private final File counterFile;
    private long counter;
    private static final class Record {
        final byte[] fingerprint;
        final CompletableFuture<Long> result = new CompletableFuture<Long>();
        Record(byte[] fingerprint) { this.fingerprint = fingerprint; }
    }
    IdempotencyServer(File file) throws IOException {
        counterFile = file;
        counter = file.exists() ? Long.parseLong(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim()) : 0;
    }
    private synchronized long effect() throws IOException {
        long next = counter + 1;
        try (RandomAccessFile file = new RandomAccessFile(counterFile, "rw")) {
            file.setLength(0);
            file.write(Long.toString(next).getBytes(StandardCharsets.UTF_8));
            file.getChannel().force(true);
        }
        counter = next;
        return next;
    }
    private static void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = x.getResponseBody()) { out.write(bytes); }
    }
    private void handle(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) { reply(x, 405, "POST required"); return; }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] chunk = new byte[512]; int n;
        try (InputStream input = x.getRequestBody()) {
            while ((n = input.read(chunk)) != -1) {
                if (body.size() + n > MAX_BODY) { reply(x, 413, "body too large"); return; }
                body.write(chunk, 0, n);
            }
        }
        String key = x.getRequestHeaders().getFirst("Idempotency-Key");
        if (key != null && !key.matches("[A-Za-z0-9_-]{1,64}")) { reply(x, 400, "invalid key"); return; }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray());
            Record record = null; boolean owner = true;
            if (key != null) {
                synchronized (records) {
                    record = records.get(key);
                    if (record == null) {
                        if (records.size() >= CAPACITY) { reply(x, 429, "registry full"); return; }
                        record = new Record(hash); records.put(key, record);
                    } else {
                        if (!Arrays.equals(record.fingerprint, hash)) { reply(x, 409, "key/body conflict"); return; }
                        owner = false;
                    }
                }
            }
            long value;
            if (owner) {
                try {
                    // Delay occurs after admission: duplicate clients meet an in-flight record.
                    if ("yes".equals(x.getRequestHeaders().getFirst("X-Test-Slow"))) Thread.sleep(120);
                    value = effect();
                    // Deliberate process crash in the gap between durable effect and dedup completion.
                    if ("yes".equals(x.getRequestHeaders().getFirst("X-Test-Crash"))) Runtime.getRuntime().halt(23);
                    if (record != null) record.result.complete(value);
                } catch (Exception failure) {
                    if (record != null) record.result.completeExceptionally(failure);
                    throw failure;
                }
            } else { value = record.result.get(5, TimeUnit.SECONDS); }
            x.getResponseHeaders().set("Idempotency-Replayed", Boolean.toString(!owner));
            if ("yes".equals(x.getRequestHeaders().getFirst("X-Test-Drop"))) { x.close(); return; }
            reply(x, 200, Long.toString(value));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); reply(x, 503, "interrupted"); }
          catch (Exception e) { reply(x, 503, "result unavailable"); }
    }
    public static void main(String[] args) throws Exception {
        IdempotencyServer app = new IdempotencyServer(new File(args[0]));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        server.createContext("/effect", app::handle);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("READY " + server.getAddress().getPort()); System.out.flush();
    }
}
