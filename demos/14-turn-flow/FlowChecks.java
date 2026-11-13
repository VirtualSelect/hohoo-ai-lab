package com.hohoo.ailab.bounded;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Fixed synthetic inputs over real loopback HTTP. No model is involved. */
public final class FlowChecks {
    private static final String SYSTEM = "Local integration fixture, not a model answer.";
    private final List<JsonObject> requests = Collections.synchronizedList(new ArrayList<JsonObject>());
    private final List<JsonObject> results = new ArrayList<>();
    private final Path output;
    private HttpServer server;
    private FlowChecks(Path output) { this.output = output; }
    interface Action { void run() throws Exception; }
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static String rejected(Action action, Class<? extends Throwable> expected) throws Exception {
        try { action.run(); }
        catch (Throwable error) {
            if (expected.isInstance(error)) return error.getClass().getSimpleName() + ": " + error.getMessage();
            throw new AssertionError("wrong failure type", error);
        }
        throw new AssertionError("operation unexpectedly succeeded");
    }
    private void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                require("POST".equals(exchange.getRequestMethod()), "POST required");
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                try (InputStream body = exchange.getRequestBody()) {
                    int b;
                    while ((b = body.read()) != -1) {
                        if (data.size() >= 8192) throw new IOException("fixture request too large");
                        data.write(b);
                    }
                }
                JsonObject payload = JsonParser.parseString(new String(data.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject record = new JsonObject();
                record.addProperty("case", exchange.getRequestURI().getRawQuery().substring("case=".length()));
                record.addProperty("path", exchange.getRequestURI().getPath());
                record.addProperty("bytes", data.size());
                record.add("payload", payload);
                requests.add(record);
                String mode = exchange.getRequestURI().getPath();
                if ("/lost".equals(mode)) return; // Accepted the body, then lost the response.
                int status = "/unavailable".equals(mode) ? 503 : 200;
                JsonArray messages = payload.getAsJsonArray("messages");
                String question = messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
                String reply = "/blank".equals(mode) ? "   " : "fixture:" + question;
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                if ("/answer-bytes".equals(mode)) {
                    StringBuilder text = new StringBuilder();
                    for (int i = 0; i < 171; i++) text.append('\u4e2d');
                    bytes = text.toString().getBytes(StandardCharsets.UTF_8); // 171 chars, 513 bytes.
                }
                if ("/malformed-utf8".equals(mode)) bytes = new byte[]{(byte)0xc3, 0x28};
                if ("/response-limit".equals(mode)) {
                    bytes = new byte[4097]; Arrays.fill(bytes, (byte)'x');
                }
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream body = exchange.getResponseBody()) { body.write(bytes); }
            } finally { exchange.close(); }
        });
        server.start();
    }
    private FixtureReplyClient client(String mode, String name) throws MalformedURLException {
        return new FixtureReplyClient(new URL("http://127.0.0.1:" + server.getAddress().getPort()
                + "/" + mode + "?case=" + name));
    }
    private TurnFlow flow(TurnJournal journal, TurnFlow.ReplySource source) {
        return new TurnFlow(journal, SYSTEM, 2048, 512, source);
    }
    private int count(String name) {
        synchronized (requests) {
            return (int)requests.stream().filter(r -> name.equals(r.get("case").getAsString())).count();
        }
    }
    private JsonObject row(String name, String outcome) throws IOException {
        Path journal = output.resolve(name + ".log");
        JsonObject row = new JsonObject();
        row.addProperty("name", name); row.addProperty("outcome", outcome);
        row.addProperty("httpRequests", count(name));
        row.addProperty("committedTurns", Files.readAllLines(journal, StandardCharsets.US_ASCII).size());
        row.addProperty("journal", journal.getFileName().toString());
        results.add(row);
        return row;
    }
    private void run() throws Exception {
        String name = "two-turns";
        try (TurnJournal journal = new TurnJournal(output.resolve(name + ".log"))) {
            TurnFlow flow = flow(journal, client("ok", name));
            require("committed".equals(flow.ask("a", "alpha", 5000, new AtomicBoolean()).state), "first commit");
            TurnFlow.Result second = flow.ask("b", "beta", 5000, new AtomicBoolean());
            require(second.kept == 1 && journal.history().size() == 2, "second request carries first turn");
            JsonArray messages = requests.get(requests.size() - 1).getAsJsonObject("payload").getAsJsonArray("messages");
            require(messages.size() == 4, "system, complete history, current question");
            require("fixture:alpha".equals(messages.get(2).getAsJsonObject().get("content").getAsString()), "committed answer in request");
        }
        row(name, "committed");

        name = "project-not-delete";
        try (TurnJournal journal = new TurnJournal(output.resolve(name + ".log"))) {
            journal.commit("old", "old fact", "old answer");
            journal.commit("recent", "recent fact", "recent answer");
            int limit = ContextBudget.request(SYSTEM, journal.history().subList(1, 2), "new question").length;
            TurnFlow.Result result = new TurnFlow(journal, SYSTEM, limit, 512, client("ok", name))
                    .ask("new", "new question", 5000, new AtomicBoolean());
            require(result.kept == 1 && result.dropped == 1 && result.requestBytes <= limit, "request projection");
            require(journal.find("old") != null && journal.history().size() == 3, "projection did not delete history");
        }
        row(name, "committed");

        name = "reopen-replay-conflict";
        Path replayPath = output.resolve(name + ".log");
        try (TurnJournal journal = new TurnJournal(replayPath)) {
            flow(journal, client("ok", name)).ask("same", "question", 5000, new AtomicBoolean());
        }
        final String replayCase = name;
        String conflict;
        try (TurnJournal journal = new TurnJournal(replayPath)) {
            TurnFlow flow = flow(journal, client("ok", name));
            require("replayed".equals(flow.ask("same", "question", 5000, new AtomicBoolean()).state), "replay after reopen");
            conflict = rejected(() -> flow.ask("same", "different", 5000, new AtomicBoolean()), IOException.class);
            require(count(replayCase) == 1, "replay and conflicting ID do not dispatch");
        }
        row(name, "replayed-and-conflict-rejected").addProperty("failure", conflict);

        for (String mode : Arrays.asList("budget", "blank", "unavailable", "lost", "cancel-before", "cancel-after", "deadline-after", "interrupt-before", "answer-bytes", "malformed-utf8", "response-limit")) {
            final String caseName = mode;
            AtomicBoolean cancelled = new AtomicBoolean("cancel-before".equals(mode));
            AtomicLong clock = new AtomicLong(1L);
            String failure;
            try (TurnJournal journal = new TurnJournal(output.resolve(caseName + ".log"))) {
                journal.commit("seed", "seed question", "seed answer");
                FixtureReplyClient http = client(Arrays.asList("blank", "unavailable", "lost", "answer-bytes", "malformed-utf8", "response-limit").contains(mode) ? mode : "ok", caseName);
                TurnFlow.ReplySource source = (payload, timeout) -> {
                    String reply = http.generateOnce(payload, timeout);
                    if ("cancel-after".equals(caseName)) cancelled.set(true);
                    if ("deadline-after".equals(caseName)) clock.set(10_000_000_000L);
                    return reply;
                };
                TurnFlow flow = new TurnFlow(journal, SYSTEM, "budget".equals(mode) ? 8 : 2048, 512,
                        source, "deadline-after".equals(mode) ? clock::get : System::nanoTime);
                if ("interrupt-before".equals(mode)) Thread.currentThread().interrupt();
                try {
                    failure = rejected(() -> flow.ask("id", "question", 5000, cancelled),
                            "budget".equals(mode) ? IllegalArgumentException.class : IOException.class);
                    if ("interrupt-before".equals(mode)) require(Thread.currentThread().isInterrupted(), "interrupt flag preserved");
                } finally {
                    if ("interrupt-before".equals(mode)) Thread.interrupted();
                }
                require(journal.history().size() == 1 && "seed answer".equals(journal.find("seed").answer),
                        "failure preserved the existing turn and did not commit the candidate");
            }
            row(caseName, "rejected").addProperty("failure", failure);
        }

        name = "owner-thread";
        try (TurnJournal journal = new TurnJournal(output.resolve(name + ".log"))) {
            journal.commit("seed", "seed question", "seed answer");
            TurnFlow flow = flow(journal, client("ok", name));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try { flow.ask("id", "question", 5000, new AtomicBoolean()); }
                catch (Throwable error) { failure.set(error); }
            });
            other.start(); other.join(2000);
            require(!other.isAlive() && failure.get() instanceof IllegalStateException, "another owner rejected");
        }
        row(name, "rejected");

        name = "commit-io-poisons-flow";
        TurnJournal closed = new TurnJournal(output.resolve(name + ".log"));
        closed.commit("seed", "seed question", "seed answer");
        TurnFlow poisoned = flow(closed, client("ok", name));
        closed.close(); // Real ClosedChannelException, not a power-loss experiment.
        String commitFailure = rejected(() -> poisoned.ask("id", "question", 5000, new AtomicBoolean()), IOException.class);
        rejected(() -> poisoned.ask("next", "question", 5000, new AtomicBoolean()), IOException.class);
        row(name, "reopen-required").addProperty("failure", commitFailure);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.write(output.resolve("results.json"), gson.toJson(results).getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("requests.json"), gson.toJson(requests).getBytes(StandardCharsets.UTF_8));
    }
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.createDirectory(output);
        FlowChecks checks = new FlowChecks(output);
        try { checks.start(); checks.run(); }
        finally { if (checks.server != null) checks.server.stop(0); }
        System.out.println("Local turn-flow fixtures completed; not a model evaluation.");
    }
}
