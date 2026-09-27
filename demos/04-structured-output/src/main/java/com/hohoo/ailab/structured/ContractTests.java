package com.hohoo.ailab.structured;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

public final class ContractTests {
    static JsonArray records = new JsonArray();
    static String ok = "{\"category\":\"ai-apps\",\"tags\":[\"Java\",\"JSON\"]}";
    static void check(String id, String text, boolean expected) throws Exception {
        boolean accepted;
        try { Classification.parse(text); accepted = true; } catch (IOException | RuntimeException e) { accepted = false; }
        if (accepted != expected) throw new AssertionError(id);
        JsonObject row = StructuredOutput.record(id, "synthetic-fixture");
        row.addProperty("input", text); row.addProperty("expectedAccepted", expected);
        row.addProperty("actualAccepted", accepted); records.add(row);
    }
    static JsonObject envelope(String content, String finish) {
        JsonObject msg = new JsonObject(); msg.addProperty("role", "assistant"); msg.addProperty("content", content);
        JsonObject choice = new JsonObject(); choice.add("message", msg); choice.addProperty("finish_reason", finish);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject root = new JsonObject(); root.add("choices", choices); return root;
    }
    static void envelopeCheck(String id, JsonObject data, boolean expected) throws Exception {
        boolean accepted;
        try { Classification.assistant(data); accepted = true; } catch (IOException | RuntimeException e) { accepted = false; }
        if (accepted != expected) throw new AssertionError(id);
        JsonObject row = StructuredOutput.record(id, "synthetic-envelope");
        row.addProperty("actualAccepted", accepted); row.addProperty("expectedAccepted", expected); records.add(row);
    }
    public static void run() throws Exception {
        check("valid", ok, true);
        check("review", "{\"category\":\"needs-review\",\"tags\":[\"待审核\"]}", true);
        check("markdown", "```json\n" + ok + "\n```", false);
        check("unknown-category", ok.replace("ai-apps", "java"), false);
        check("numeric-tag", "{\"category\":\"llm\",\"tags\":[12]}", false);
        check("empty-tags", "{\"category\":\"llm\",\"tags\":[]}", false);
        check("four-tags", "{\"category\":\"llm\",\"tags\":[\"a\",\"b\",\"c\",\"d\"]}", false);
        check("duplicate-tag", "{\"category\":\"llm\",\"tags\":[\"a\",\"a\"]}", false);
        check("unknown-field", "{\"category\":\"llm\",\"tags\":[\"a\"],\"command\":\"delete\"}", false);
        check("duplicate-field", "{\"category\":\"llm\",\"category\":\"ai-apps\",\"tags\":[\"a\"]}", false);
        check("missing-field", "{\"category\":\"llm\"}", false);
        check("null", "{\"category\":null,\"tags\":[\"a\"]}", false);
        check("not-json", "这里是结果：" + ok, false);
        check("single-quotes", "{'category':'llm','tags':['a']}", false);
        check("trailing-json", ok + "{}", false);
        check("blank-tag", "{\"category\":\"llm\",\"tags\":[\" \"]}", false);
        check("control-tag", "{\"category\":\"llm\",\"tags\":[\"a\\nb\"]}", false);
        check("control-c1-tag", "{\"category\":\"llm\",\"tags\":[\"a\\u0085b\"]}", false);
        check("long-tag", "{\"category\":\"llm\",\"tags\":[\"abcdefghijklmnopqrstuvwxyz\"]}", false);
        check("semantic-mistake-still-valid", "{\"category\":\"llm\",\"tags\":[\"炒饭\"]}", true);
        envelopeCheck("finished", envelope(ok, "stop"), true);
        envelopeCheck("truncated", envelope(ok, "length"), false);
        envelopeCheck("empty-choices", new JsonObject(), false);
        envelopeCheck("empty-content", envelope("", "stop"), false);
        String input = "中文 \"接口\"\nC:\\tmp";
        String decoded = StructuredOutput.request(input).getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        if (!input.equals(decoded)) throw new AssertionError("escaping");
        JsonObject escape = StructuredOutput.record("input-escaping", "synthetic-fixture");
        escape.addProperty("passed", true); records.add(escape);
        adapter("adapter-plain", ok, true);
        adapter("adapter-single-fence", "\n\n```json\n" + ok + "\n```", true);
        adapter("adapter-prose", "Result:\n```json\n" + ok + "\n```", false);
        adapter("adapter-two-fences", "```json\n" + ok + "\n```\n```json\n" + ok + "\n```", false);
        adapter("adapter-still-checks-enum", "```json\n" + ok.replace("ai-apps", "java") + "\n```", false);
        transport();
        JsonObject report = StructuredOutput.record("offline-tests", "offline-test-run");
        report.addProperty("javaVersion", System.getProperty("java.version"));
        report.addProperty("passed", records.size()); report.add("tests", records);
        StructuredOutput.save(Paths.get("evidence/offline-" + System.currentTimeMillis() + ".json"), report);
        System.out.println(records.size() + " offline checks passed. Fixtures are not model outputs.");
    }
    static void adapter(String id, String value, boolean expected) throws Exception {
        boolean accepted;
        try { Classification.parse(ContentFormat.unwrapOneJsonFence(value)); accepted = true; }
        catch (IOException e) { accepted = false; }
        if (accepted != expected) throw new AssertionError(id);
        JsonObject row = StructuredOutput.record(id, "synthetic-format-adapter");
        row.addProperty("actualAccepted", accepted); row.addProperty("expectedAccepted", expected); records.add(row);
    }
    static void transport() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.ExecutorService pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        for (final String path : new String[]{"ok","error","redirect","slow","invalid"}) {
            server.createContext("/" + path, exchange -> {
                try {
                    if ("slow".equals(path)) Thread.sleep(200);
                    int status = "error".equals(path) ? 500 : "redirect".equals(path) ? 302 : 200;
                    if (status == 302) exchange.getResponseHeaders().add("Location", "/ok");
                    byte[] body = ("invalid".equals(path) ? "broken" : envelope(ok, "stop").toString()).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (IOException ignored) { /* Expected when the timeout test closes its socket. */ }
                finally { exchange.close(); }
            });
        }
        server.start();
        try {
            for (String path : new String[]{"ok","error","redirect","slow","invalid"}) {
                boolean accepted;
                try {
                    JsonObject result = new ChatClient("http://127.0.0.1:" + server.getAddress().getPort() + "/" + path,
                        "fixture-not-a-secret", "slow".equals(path) ? 50 : 1000, true).post(StructuredOutput.request("fixture"));
                    Classification.parse(Classification.assistant(result).get("content").getAsString()); accepted = true;
                } catch (IOException e) { accepted = false; }
                if (accepted != "ok".equals(path)) throw new AssertionError("transport-" + path);
                JsonObject row = StructuredOutput.record("transport-" + path, "loopback-http-fixture");
                row.addProperty("actualAccepted", accepted); records.add(row);
            }
        } finally { server.stop(0); pool.shutdownNow(); }
    }
}
