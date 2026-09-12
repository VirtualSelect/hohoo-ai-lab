package com.hohoo.ailab.response;

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
import java.util.Scanner;

/**
 * 第二课：用成熟 JSON 库构造请求，并从 LLM 响应中提取我们真正关心的字段。
 */
public class ParseLlmResponse {
    private static final Gson GSON = new Gson();
    private static final String API_URL = "https://apihub.agnes-ai.com/v1/chat/completions";
    private static final String ENV_API_KEY = "AGNES_API_KEY";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 90_000;

    public static void main(String[] args) {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            runSelfTest();
            return;
        }

        String question = readQuestionFromConsole();
        if (question == null) {
            System.exit(1);
            return;
        }

        String apiKey = System.getenv(ENV_API_KEY);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.err.println("缺少环境变量 " + ENV_API_KEY + "，未发送请求。");
            System.err.println("请先在运行配置或当前终端中配置密钥，再重新运行本示例。");
            System.exit(1);
            return;
        }

        int exitCode = runRealRequest(apiKey, question);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static String readQuestionFromConsole() {
        System.out.print("请输入问题，然后按回车：");
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8.name());
        if (!scanner.hasNextLine()) {
            System.err.println("没有读取到问题，未发送请求。");
            return null;
        }

        String question = scanner.nextLine();
        if (question.trim().isEmpty()) {
            System.err.println("问题不能为空，未发送请求。");
            return null;
        }
        return question;
    }

    private static int runRealRequest(String apiKey, String question) {
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

            byte[] requestBytes = buildRequestJson(question).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBytes);
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection, statusCode);

            System.out.println("HTTP 状态码: " + statusCode);

            if (statusCode < 200 || statusCode >= 300) {
                System.err.println("接口返回错误正文:");
                System.err.println(responseBody);
                return 1;
            }

            ParsedResponse parsed = parseAssistantResponse(responseBody);
            printParsedResponse(parsed);
            return 0;
        } catch (SocketTimeoutException e) {
            System.err.println("请求等待超时: " + e.getMessage());
            System.err.println("当前连接超时上限为 10 秒，读取等待上限为 90 秒。");
            System.err.println("这可能是网络较慢或服务端响应较慢；仅凭这个错误无法确认服务端是否已经处理请求。");
            System.err.println("程序不会自动重试。如需重试，请确认风险后手动再运行一次，重试可能再次计费。");
            return 1;
        } catch (IOException e) {
            System.err.println("请求失败: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("解析失败: " + e.getMessage());
            return 1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String buildRequestJson(String question) {
        JsonObject root = new JsonObject();
        root.addProperty("model", "agnes-2.5-flash");

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", question);
        messages.add(userMessage);

        root.add("messages", messages);
        return GSON.toJson(root);
    }

    static ParsedResponse parseAssistantResponse(String responseJson) {
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

            // 解析路径：String -> JSON对象 -> 数组第0项 -> message -> content。
            JsonObject firstChoice = firstChoiceElement.getAsJsonObject();
            JsonObject message = requiredObject(firstChoice, "message");
            String content = requiredString(message, "content");
            if (content.trim().isEmpty()) {
                throw new IllegalArgumentException("message.content 为空，无法展示模型回答。");
            }

            Long promptTokens = null;
            Long completionTokens = null;
            Long totalTokens = null;
            JsonElement usageElement = root.get("usage");
            if (usageElement != null && !usageElement.isJsonNull()) {
                if (!usageElement.isJsonObject()) {
                    throw new IllegalArgumentException("usage 不是 JSON 对象。");
                }
                JsonObject usage = usageElement.getAsJsonObject();
                promptTokens = optionalLong(usage, "prompt_tokens");
                completionTokens = optionalLong(usage, "completion_tokens");
                totalTokens = optionalLong(usage, "total_tokens");
            }

            return new ParsedResponse(content, promptTokens, completionTokens, totalTokens);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("响应不是合法 JSON: " + e.getMessage(), e);
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

    private static void printParsedResponse(ParsedResponse parsed) {
        System.out.println("模型回答:");
        System.out.println(parsed.content.trim());

        if (parsed.totalTokens == null) {
            System.out.println("Token 用量: 接口未提供 usage.total_tokens");
            return;
        }

        System.out.println("Token 用量:");
        if (parsed.promptTokens != null) {
            System.out.println("  prompt_tokens: " + parsed.promptTokens);
        }
        if (parsed.completionTokens != null) {
            System.out.println("  completion_tokens: " + parsed.completionTokens);
        }
        System.out.println("  total_tokens: " + parsed.totalTokens);
    }

    private static void runSelfTest() {
        String fixtureResponse = "{"
                + "\"id\":\"fixture-chatcmpl-001\","
                + "\"choices\":[{"
                + "\"index\":0,"
                + "\"message\":{"
                + "\"role\":\"assistant\","
                + "\"reasoning_content\":\"这里是fixture里的推理字段，自检不会输出它。\","
                + "\"content\":\"大语言模型是一种通过大量文本学习语言规律的人工智能模型。它可以根据上下文生成、总结、改写或回答自然语言内容。\""
                + "},"
                + "\"finish_reason\":\"stop\""
                + "}],"
                + "\"usage\":{"
                + "\"prompt_tokens\":293,"
                + "\"completion_tokens\":104,"
                + "\"total_tokens\":397"
                + "}"
                + "}";

        ParsedResponse parsed = parseAssistantResponse(fixtureResponse);
        assertEquals("fixture content",
                "大语言模型是一种通过大量文本学习语言规律的人工智能模型。它可以根据上下文生成、总结、改写或回答自然语言内容。",
                parsed.content);
        assertEquals("fixture prompt_tokens", Long.valueOf(293), parsed.promptTokens);
        assertEquals("fixture completion_tokens", Long.valueOf(104), parsed.completionTokens);
        assertEquals("fixture total_tokens", Long.valueOf(397), parsed.totalTokens);

        assertParseError("missing choices", "{\"id\":\"fixture-missing-choices\"}", "choices");
        assertParseError("empty choices", "{\"choices\":[]}", "为空");
        assertRequestJsonContentRoundTrip();

        System.out.println("fixture 自检通过：已提取 choices[0].message.content 和 usage token 字段。");
        System.out.println("fixture 自检通过：缺失或空 choices 会抛出清晰错误。");
        System.out.println("fixture 自检通过：中文、双引号和反斜杠输入可以正确构造为 JSON。");
    }

    private static void assertRequestJsonContentRoundTrip() {
        String question = "请解释 \"JSON\" 和路径 C:\\\\temp\\\\demo 的区别";
        JsonObject root = JsonParser.parseString(buildRequestJson(question)).getAsJsonObject();
        JsonArray messages = requiredArray(root, "messages");
        JsonObject firstMessage = messages.get(0).getAsJsonObject();
        assertEquals("request content round trip", question, requiredString(firstMessage, "content"));
    }

    private static void assertEquals(String name, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(name + " 断言失败，expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertParseError(String name, String json, String expectedMessagePart) {
        try {
            parseAssistantResponse(json);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains(expectedMessagePart)) {
                return;
            }
            throw new IllegalStateException(name + " 错误消息不符合预期: " + e.getMessage(), e);
        }
        throw new IllegalStateException(name + " 应该解析失败，但实际成功。");
    }

    static class ParsedResponse {
        final String content;
        final Long promptTokens;
        final Long completionTokens;
        final Long totalTokens;

        ParsedResponse(String content, Long promptTokens, Long completionTokens, Long totalTokens) {
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
    }
}
