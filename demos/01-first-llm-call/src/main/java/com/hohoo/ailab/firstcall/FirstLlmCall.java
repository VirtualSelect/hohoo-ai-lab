package com.hohoo.ailab.firstcall;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 第一课：用 Java 8 标准库完成一次最小 LLM 调用。
 *
 * 这个示例故意不使用 SDK、Maven、Spring AI 或 JSON 解析库，方便先看清楚一次 HTTP 调用
 * 最核心的组成：地址、鉴权、请求体、响应状态和原始 JSON。
 */
public class FirstLlmCall {
    private static final String API_URL = "https://apihub.agnes-ai.com/v1/chat/completions";
    private static final String ENV_API_KEY = "AGNES_API_KEY";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    public static void main(String[] args) {
        String apiKey = System.getenv(ENV_API_KEY);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.err.println("缺少环境变量 " + ENV_API_KEY + "，未发送请求。");
            System.err.println("请先在新终端中配置密钥，再重新运行本示例。");
            System.exit(1);
            return;
        }

        HttpURLConnection connection = null;
        int exitCode = 0;
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

            byte[] requestBytes = buildRequestJson().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBytes);
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection, statusCode);

            System.out.println("HTTP 状态码: " + statusCode);
            System.out.println("原始 JSON 响应:");
            System.out.println(responseBody);

            if (statusCode < 200 || statusCode >= 300) {
                exitCode = 1;
            }
        } catch (IOException e) {
            System.err.println("请求失败: " + e.getMessage());
            exitCode = 1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static String buildRequestJson() {
        return "{"
                + "\"model\":\"agnes-2.5-flash\","
                + "\"messages\":["
                + "{"
                + "\"role\":\"user\","
                + "\"content\":\"请用两句话解释什么是大语言模型。\""
                + "}"
                + "]"
                + "}";
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
}
