package com.hohoo.ailab.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 第三课：多轮对话。
 *
 * 多轮的关键不是特殊接口，而是每次请求都把已经成功的 messages 历史重新发送给模型。
 * 这个 Demo 只把历史保存在内存里，程序退出后历史会清空。
 */
public class MultiTurnChat {
    private static final Gson GSON = new Gson();
    private static final String API_URL = "https://apihub.agnes-ai.com/v1/chat/completions";
    private static final String ENV_API_KEY = "AGNES_API_KEY";
    private static final String MODEL = "agnes-2.5-flash";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 90_000;

    public static void main(String[] args) {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            runSelfTest();
            return;
        }

        String apiKey = System.getenv(ENV_API_KEY);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.err.println("缺少环境变量 " + ENV_API_KEY + "，未开始对话。");
            System.err.println("请先在运行配置或当前终端中配置密钥，再重新运行本示例。");
            System.exit(1);
            return;
        }

        ChatSession session = new ChatSession(apiKey);
        session.startConsoleLoop(new Scanner(System.in, StandardCharsets.UTF_8.name()));
    }

    static class ChatSession {
        private final String apiKey;
        private final List<ChatMessage> history = new ArrayList<ChatMessage>();

        ChatSession(String apiKey) {
            this.apiKey = apiKey;
        }

        void startConsoleLoop(Scanner scanner) {
            System.out.println("多轮对话已开始。输入 exit 后回车可退出。");
            while (true) {
                UserInput input = readUserInput(scanner);
                if (input.exit) {
                    System.out.println("已退出，多轮历史只保存在本次进程内。");
                    return;
                }
                if (!input.accepted) {
                    System.out.println(input.message);
                    continue;
                }

                boolean success = sendOneTurn(input.content);
                if (!success) {
                    System.err.println("本轮请求失败，已撤回本次 user 消息；之前成功的对话历史仍然保留。");
                }
            }
        }

        boolean sendOneTurn(String question) {
            history.add(new ChatMessage("user", question));
            try {
                ChatResponse response = request(history);
                System.out.println("助手：");
                System.out.println(response.content.trim());

                if (response.finishReason != null && !"stop".equals(response.finishReason)) {
                    System.out.println("提示：finish_reason=" + response.finishReason + "，模型可能没有完整停止在自然结尾。");
                }

                printUsage(response.usage);
                history.add(new ChatMessage("assistant", response.content));
                return true;
            } catch (RequestFailedException e) {
                System.err.println(e.getMessage());
                removeLastUserMessage();
                return false;
            }
        }

        private ChatResponse request(List<ChatMessage> messages) throws RequestFailedException {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(API_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json");

                byte[] requestBytes = buildRequestJson(messages).getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(requestBytes.length);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(requestBytes);
                }

                int statusCode = connection.getResponseCode();
                String responseBody = readResponseBody(connection, statusCode);

                if (statusCode < 200 || statusCode >= 300) {
                    throw new RequestFailedException("接口返回非 2xx 状态码 " + statusCode + "，错误正文:\n" + responseBody);
                }

                return parseAssistantResponse(responseBody);
            } catch (SocketTimeoutException e) {
                throw new RequestFailedException("请求等待超时: " + e.getMessage()
                        + "\n当前连接超时上限为 10 秒，读取等待上限为 90 秒。"
                        + "\n这可能是网络较慢或服务端响应较慢；仅凭这个错误无法确认服务端是否已经处理请求。"
                        + "\n程序不会自动重试。如需重试，请确认风险后手动再输入一次，重试可能再次计费。", e);
            } catch (IOException e) {
                throw new RequestFailedException("请求失败: " + e.getMessage(), e);
            } catch (IllegalArgumentException e) {
                throw new RequestFailedException("解析失败: " + e.getMessage(), e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private void removeLastUserMessage() {
            if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).role)) {
                history.remove(history.size() - 1);
            }
        }

        List<ChatMessage> historySnapshot() {
            return new ArrayList<ChatMessage>(history);
        }
    }

    static UserInput readUserInput(Scanner scanner) {
        System.out.print("你：");
        if (!scanner.hasNextLine()) {
            return UserInput.exit();
        }

        String line = scanner.nextLine();
        String trimmed = line.trim();
        if ("exit".equalsIgnoreCase(trimmed)) {
            return UserInput.exit();
        }
        if (trimmed.isEmpty()) {
            return UserInput.rejected("问题不能为空，请重新输入；输入 exit 可退出。");
        }
        return UserInput.accepted(line);
    }

    static String buildRequestJson(List<ChatMessage> history) {
        JsonObject root = new JsonObject();
        root.addProperty("model", MODEL);

        JsonArray messages = new JsonArray();
        for (ChatMessage message : history) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role);
            item.addProperty("content", message.content);
            messages.add(item);
        }

        root.add("messages", messages);
        return GSON.toJson(root);
    }

    static ChatResponse parseAssistantResponse(String responseJson) {
        try {
            JsonElement rootElement = JsonParser.parseString(responseJson);
            if (!rootElement.isJsonObject()) {
                throw new IllegalArgumentException("响应根节点不是 JSON 对象。");
            }

            JsonObject root = rootElement.getAsJsonObject();
            JsonArray choices = requiredArray(root, "choices");
            if (choices.size() == 0) {
                throw new IllegalArgumentException("响应字段 choices 为空，无法读取模型回答。");
            }

            JsonElement firstChoiceElement = choices.get(0);
            if (!firstChoiceElement.isJsonObject()) {
                throw new IllegalArgumentException("choices[0] 不是 JSON 对象。");
            }

            // 解析路径：String -> JSON对象 -> choices数组第0项 -> message -> content。
            JsonObject firstChoice = firstChoiceElement.getAsJsonObject();
            JsonObject message = requiredObject(firstChoice, "message");
            String content = requiredString(message, "content");
            if (content.trim().isEmpty()) {
                throw new IllegalArgumentException("message.content 为空，无法展示模型回答。");
            }

            String finishReason = optionalString(firstChoice, "finish_reason");
            Usage usage = parseUsage(root);
            return new ChatResponse(content, finishReason, usage);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("响应不是合法 JSON: " + e.getMessage(), e);
        }
    }

    private static Usage parseUsage(JsonObject root) {
        JsonElement usageElement = root.get("usage");
        if (usageElement == null || usageElement.isJsonNull()) {
            return null;
        }
        if (!usageElement.isJsonObject()) {
            throw new IllegalArgumentException("usage 不是 JSON 对象。");
        }

        JsonObject usage = usageElement.getAsJsonObject();
        return new Usage(
                optionalLong(usage, "prompt_tokens"),
                optionalLong(usage, "completion_tokens"),
                optionalLong(usage, "total_tokens"));
    }

    private static void printUsage(Usage usage) {
        if (usage == null || usage.totalTokens == null) {
            System.out.println("Token 用量: 接口未提供 usage.total_tokens");
            return;
        }

        System.out.println("Token 用量:");
        if (usage.promptTokens != null) {
            System.out.println("  prompt_tokens: " + usage.promptTokens);
        }
        if (usage.completionTokens != null) {
            System.out.println("  completion_tokens: " + usage.completionTokens);
        }
        System.out.println("  total_tokens: " + usage.totalTokens);
    }

    private static String readResponseBody(HttpURLConnection connection, int statusCode) throws IOException {
        InputStream inputStream = statusCode >= 200 && statusCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();

        if (inputStream == null) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append(System.lineSeparator());
            }
            return body.toString().trim();
        }
    }

    private static JsonArray requiredArray(JsonObject object, String fieldName) {
        JsonElement element = object.get(fieldName);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("响应缺少字段 " + fieldName + "。");
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("字段 " + fieldName + " 不是 JSON 数组。");
        }
        return element.getAsJsonArray();
    }

    private static JsonObject requiredObject(JsonObject object, String fieldName) {
        JsonElement element = object.get(fieldName);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("响应缺少字段 " + fieldName + "。");
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("字段 " + fieldName + " 不是 JSON 对象。");
        }
        return element.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String fieldName) {
        JsonElement element = object.get(fieldName);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("响应缺少字段 " + fieldName + "。");
        }
        if (!element.isJsonPrimitive()) {
            throw new IllegalArgumentException("字段 " + fieldName + " 不是字符串。");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw new IllegalArgumentException("字段 " + fieldName + " 不是字符串。");
        }
        return primitive.getAsString();
    }

    private static String optionalString(JsonObject object, String fieldName) {
        JsonElement element = object.get(fieldName);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("字段 " + fieldName + " 不是字符串。");
        }
        return element.getAsString();
    }

    private static Long optionalLong(JsonObject object, String fieldName) {
        JsonElement element = object.get(fieldName);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("字段 usage." + fieldName + " 不是数字。");
        }
        return element.getAsLong();
    }

    private static void runSelfTest() {
        assertInputHandling();
        assertBuildRequestJsonContainsFullHistory();
        assertSuccessfulHistoryOrder();
        assertFailureRollbackKeepsOldHistory();

        System.out.println("fixture 自检通过：user/assistant 历史顺序正确。");
        System.out.println("fixture 自检通过：失败会撤回本轮 user 消息并保留旧历史。");
        System.out.println("fixture 自检通过：第二次请求 JSON 包含完整历史且能正确处理转义。");
        System.out.println("fixture 自检通过：空输入和 exit 不会加入历史。");
    }

    private static void assertInputHandling() {
        UserInput blank = readUserInput(new Scanner("   \n"));
        assertEquals("blank accepted", Boolean.FALSE, Boolean.valueOf(blank.accepted));
        assertEquals("blank exit", Boolean.FALSE, Boolean.valueOf(blank.exit));

        UserInput exit = readUserInput(new Scanner(" exit \n"));
        assertEquals("exit accepted", Boolean.FALSE, Boolean.valueOf(exit.accepted));
        assertEquals("exit flag", Boolean.TRUE, Boolean.valueOf(exit.exit));

        UserInput eof = readUserInput(new Scanner(""));
        assertEquals("eof accepted", Boolean.FALSE, Boolean.valueOf(eof.accepted));
        assertEquals("eof exit", Boolean.TRUE, Boolean.valueOf(eof.exit));
    }

    private static void assertBuildRequestJsonContainsFullHistory() {
        List<ChatMessage> history = new ArrayList<ChatMessage>();
        history.add(new ChatMessage("user", "我正在学习 Java"));
        history.add(new ChatMessage("assistant", "你正在学习 Java。"));
        history.add(new ChatMessage("user", "我正在学习什么？路径 C:\\\\tmp，术语 \"接口\"。"));

        JsonObject root = JsonParser.parseString(buildRequestJson(history)).getAsJsonObject();
        JsonArray messages = requiredArray(root, "messages");
        assertEquals("message size", Integer.valueOf(3), Integer.valueOf(messages.size()));
        assertMessage(messages, 0, "user", "我正在学习 Java");
        assertMessage(messages, 1, "assistant", "你正在学习 Java。");
        assertMessage(messages, 2, "user", "我正在学习什么？路径 C:\\\\tmp，术语 \"接口\"。");
    }

    private static void assertSuccessfulHistoryOrder() {
        List<ChatMessage> history = new ArrayList<ChatMessage>();
        history.add(new ChatMessage("user", "我正在学习 Java"));
        ChatResponse response = parseAssistantResponse(successFixture("你正在学习 Java。", "stop"));
        history.add(new ChatMessage("assistant", response.content));

        assertEquals("history size", Integer.valueOf(2), Integer.valueOf(history.size()));
        assertEquals("history role 0", "user", history.get(0).role);
        assertEquals("history role 1", "assistant", history.get(1).role);
    }

    private static void assertFailureRollbackKeepsOldHistory() {
        List<ChatMessage> history = new ArrayList<ChatMessage>();
        history.add(new ChatMessage("user", "第一轮"));
        history.add(new ChatMessage("assistant", "第一轮回答"));
        history.add(new ChatMessage("user", "这一轮将失败"));
        if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).role)) {
            history.remove(history.size() - 1);
        }

        assertEquals("rollback size", Integer.valueOf(2), Integer.valueOf(history.size()));
        assertEquals("rollback old assistant", "第一轮回答", history.get(1).content);
    }

    private static String successFixture(String content, String finishReason) {
        JsonObject root = new JsonObject();
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("reasoning_content", "fixture里的推理字段，自检不会保存。");
        message.addProperty("content", content);
        choice.add("message", message);
        choice.addProperty("finish_reason", finishReason);
        choices.add(choice);
        root.add("choices", choices);

        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 10);
        usage.addProperty("completion_tokens", 5);
        usage.addProperty("total_tokens", 15);
        root.add("usage", usage);
        return GSON.toJson(root);
    }

    private static void assertMessage(JsonArray messages, int index, String role, String content) {
        JsonObject message = messages.get(index).getAsJsonObject();
        assertEquals("role " + index, role, requiredString(message, "role"));
        assertEquals("content " + index, content, requiredString(message, "content"));
    }

    private static void assertEquals(String name, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(name + " 断言失败，expected=" + expected + ", actual=" + actual);
        }
    }

    static class ChatMessage {
        final String role;
        final String content;

        ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    static class ChatResponse {
        final String content;
        final String finishReason;
        final Usage usage;

        ChatResponse(String content, String finishReason, Usage usage) {
            this.content = content;
            this.finishReason = finishReason;
            this.usage = usage;
        }
    }

    static class Usage {
        final Long promptTokens;
        final Long completionTokens;
        final Long totalTokens;

        Usage(Long promptTokens, Long completionTokens, Long totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
    }

    static class UserInput {
        final boolean accepted;
        final boolean exit;
        final String content;
        final String message;

        private UserInput(boolean accepted, boolean exit, String content, String message) {
            this.accepted = accepted;
            this.exit = exit;
            this.content = content;
            this.message = message;
        }

        static UserInput accepted(String content) {
            return new UserInput(true, false, content, null);
        }

        static UserInput rejected(String message) {
            return new UserInput(false, false, null, message);
        }

        static UserInput exit() {
            return new UserInput(false, true, null, null);
        }
    }

    static class RequestFailedException extends Exception {
        RequestFailedException(String message) {
            super(message);
        }

        RequestFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
