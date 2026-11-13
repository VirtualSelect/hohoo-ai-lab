package com.hohoo.ailab.bounded;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Controlled handoffs over real loopback sockets; observations are not latency benchmarks. */
public final class CancellationChecks {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    static final String END = "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void await(CountDownLatch latch, String stage) throws IOException {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IOException("fixture gate timed out: " + stage); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException(stage); }
    }
    static void send(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8)); output.flush();
    }
    static String delta(String text) {
        JsonObject part = new JsonObject(); part.addProperty("content", text);
        JsonObject choice = new JsonObject(); choice.addProperty("index", 0); choice.add("delta", part); choice.add("finish_reason", JsonNull.INSTANCE);
        JsonArray choices = new JsonArray(); choices.add(choice); JsonObject event = new JsonObject(); event.add("choices", choices);
        return "data: " + event + "\n\n";
    }
    static String headers(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int match = 0; byte[] end = {13,10,13,10};
        for (int n = 0; n < 8192; n++) {
            int b = input.read(); if (b < 0) throw new EOFException("request headers"); out.write(b);
            match = b == end[match] ? match + 1 : b == 13 ? 1 : 0;
            if (match == 4) return new String(out.toByteArray(), StandardCharsets.US_ASCII);
        }
        throw new IOException("request headers exceed fixture bound");
    }

    static final class Server implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final CountDownLatch release = new CountDownLatch(1), processed;
        final AtomicBoolean stopping = new AtomicBoolean();
        final AtomicInteger connections = new AtomicInteger(), work = new AtomicInteger(), writeErrors = new AtomicInteger();
        final List<JsonObject> requests = Collections.synchronizedList(new ArrayList<JsonObject>());
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        Server(String mode, int expected) throws IOException {
            processed = new CountDownLatch(expected);
            thread = new Thread(() -> {
                try {
                    while (!stopping.get()) {
                        try (Socket socket = listener.accept()) {
                            int number = connections.incrementAndGet(); socket.setSoTimeout(3000);
                            InputStream input = socket.getInputStream(); String header = headers(input);
                            check(header.startsWith("POST /fixture HTTP/1.1\r\n"), "fixture request method/path");
                            int length = -1;
                            for (String line : header.split("\r\n")) if (line.startsWith("Content-Length: ")) length = Integer.parseInt(line.substring(16));
                            check(length >= 0 && length <= 2048, "request byte bound");
                            byte[] body = new byte[length]; int offset = 0;
                            while (offset < body.length) { int n = input.read(body, offset, body.length-offset); if (n < 0) throw new EOFException("request body"); offset += n; }
                            JsonObject payload = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                            requests.add(payload);
                            JsonArray messages = payload.getAsJsonArray("messages");
                            String question = messages.get(messages.size()-1).getAsJsonObject().get("content").getAsString();
                            OutputStream output = socket.getOutputStream();
                            send(output, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" + delta("fixture:"));
                            work.incrementAndGet();
                            boolean gated = mode.equals("flag-only") || mode.equals("close-reading") || mode.equals("old-lease") && number == 2;
                            if (gated) await(release, "server continuation");
                            // Bounded server work continues even when the client has closed.
                            work.incrementAndGet();
                            try { send(output, delta(question) + END); }
                            catch (IOException disconnected) { writeErrors.incrementAndGet(); }
                            processed.countDown();
                        }
                    }
                } catch (Throwable error) { if (!stopping.get()) failure.set(error); }
            }, "turn-fixture-server");
            thread.start();
        }
        public void close() throws IOException {
            stopping.set(true); release.countDown(); listener.close();
            try { thread.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("server join"); }
            check(!thread.isAlive(), "server exited"); check(failure.get() == null, "server failure: " + failure.get());
        }
    }

    static Map<String,Object> run(Path out, String mode, int expectedRequests) throws Exception {
        CountDownLatch reached = new CountDownLatch(1), continueClient = new CountDownLatch(1), preview = new CountDownLatch(1), exited = new CountDownLatch(1);
        CancellationLease old = new CancellationLease(), current = mode.equals("old-lease") ? new CancellationLease() : old;
        AtomicReference<Socket> clientSocket = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> outcome = new AtomicReference<>();
        AtomicBoolean completeCandidate = new AtomicBoolean();
        Path journalPath = out.resolve(mode + ".log");
        boolean exitedAtObservation = false, exitedBeforeRelease = false, newLeaseUnaffected = false, cancelledAtObservation;
        Server server = new Server(mode, expectedRequests);
        Map<String,Object> row = new LinkedHashMap<>();
        try (Server closeAtEnd = server) {
            if (mode.equals("before-start")) old.cancel();
            SocketFixtureReplyClient.Phase phases = (phase, socket) -> {
                clientSocket.set(socket);
                if (phase.equals("reply-complete")) completeCandidate.set(true);
                if (mode.equals("before-register") && phase.equals("before-register")
                        || mode.equals("after-register") && phase.equals("registered")
                        || mode.equals("before-commit") && phase.equals("reply-complete")) {
                    reached.countDown(); await(continueClient, phase);
                }
            };
            Thread worker = new Thread(() -> {
                try (TurnJournal journal = new TurnJournal(journalPath)) {
                    journal.commit("seed", "seed question", "seed answer");
                    if (mode.equals("old-lease")) {
                        TurnFlow a = new TurnFlow(journal, "Local cancellation fixture", 2048, 512,
                                new SocketFixtureReplyClient(server.listener.getLocalPort(), old, text -> {}));
                        a.ask("a", "alpha", 5000, old.signal());
                    }
                    TurnFlow b = new TurnFlow(journal, "Local cancellation fixture", 2048, 512,
                            new SocketFixtureReplyClient(server.listener.getLocalPort(), current, text -> preview.countDown(), phases));
                    outcome.set(b.ask("b", "beta", 5000, current.signal()).state);
                } catch (Throwable error) { failure.set(error); outcome.set("rejected"); }
                finally { exited.countDown(); }
            }, "journal-owner");
            worker.start();
            try {
                if (mode.equals("before-register") || mode.equals("after-register") || mode.equals("before-commit")) {
                    await(reached, mode); current.cancel(); continueClient.countDown(); await(exited, "owner exit");
                } else if (mode.equals("flag-only") || mode.equals("close-reading")) {
                    await(preview, "first partial reply");
                    if (mode.equals("flag-only")) {
                        // Intentional control: reproduce Demo 14's flag-only cancellation.
                        current.signal().set(true);
                        exitedAtObservation = exited.await(100, TimeUnit.MILLISECONDS);
                        check(!exitedAtObservation, "flag-only reader unexpectedly exited during the controlled observation");
                    }
                    current.cancel(); // For flag-only, this is explicit cleanup after observation.
                    exitedBeforeRelease = exited.await(2, TimeUnit.SECONDS);
                    check(exitedBeforeRelease && server.release.getCount() == 1, "owner exits before server is allowed to continue");
                } else if (mode.equals("old-lease")) {
                    await(preview, "second request preview"); old.cancel();
                    newLeaseUnaffected = !current.isCancelled() && !clientSocket.get().isClosed() && exited.getCount() == 1;
                    check(newLeaseUnaffected, "old cancellation touched the new request");
                    server.release.countDown(); await(exited, "second owner exit");
                } else await(exited, "owner exit");
                cancelledAtObservation = current.isCancelled();
                server.release.countDown(); await(server.processed, "server bounded work");
            } finally {
                continueClient.countDown(); server.release.countDown();
                IOException cleanupFailure = null;
                try { current.cancel(); } catch (IOException error) { cleanupFailure = error; }
                try { old.cancel(); } catch (IOException error) { if (cleanupFailure == null) cleanupFailure = error; else cleanupFailure.addSuppressed(error); }
                worker.join(3000);
                check(!worker.isAlive(), "owner thread terminated");
                if (cleanupFailure != null) throw cleanupFailure;
            }
            check(failure.get() == null || failure.get() instanceof IOException, "unexpected worker failure: " + failure.get());
            check(!current.hasResource() && !old.hasResource(), "no retained resource");
            row.put("name", mode); row.put("outcome", outcome.get());
            row.put("receivedRequests", server.requests.size()); row.put("acceptedConnections", server.connections.get());
            row.put("serverWorkSteps", server.work.get()); row.put("serverWriteErrors", server.writeErrors.get());
            row.put("cancelledAtObservation", cancelledAtObservation);
            row.put("workerExitedAtControlObservation", mode.equals("flag-only") ? exitedAtObservation : null);
            row.put("controlObservationMs", mode.equals("flag-only") ? 100 : null);
            row.put("workerExitedBeforeServerRelease", mode.equals("flag-only") || mode.equals("close-reading") ? exitedBeforeRelease : null);
            row.put("newLeaseUnaffected", mode.equals("old-lease") ? newLeaseUnaffected : null);
            row.put("completeCandidate", completeCandidate.get());
            row.put("socketCreated", clientSocket.get() != null);
            row.put("socketClosed", clientSocket.get() == null ? null : clientSocket.get().isClosed());
            row.put("workerTerminated", !worker.isAlive()); row.put("journal", journalPath.getFileName().toString());
            row.put("failureType", failure.get() == null ? null : failure.get().getClass().getSimpleName());
            row.put("requests", new ArrayList<>(server.requests));
        }
        row.put("serverTerminated", !server.thread.isAlive());
        return row;
    }
    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[0]); Files.createDirectory(out);
        List<Map<String,Object>> results = new ArrayList<>();
        for (String mode : Arrays.asList("complete", "before-start", "before-register", "after-register", "flag-only", "close-reading", "before-commit", "old-lease"))
            results.add(run(out, mode, mode.equals("old-lease") ? 2 : mode.equals("before-start") || mode.equals("before-register") || mode.equals("after-register") ? 0 : 1));
        Files.write(out.resolve("results.json"), JSON.toJson(results).getBytes(StandardCharsets.UTF_8));
        System.out.println("Loopback cancellation handoffs completed; no provider calls.");
    }
}
