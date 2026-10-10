package com.example.server.service.ai.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class DeepSeekChatClient {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    private final String apiKey;
    private final String baseUrl;

    public DeepSeekChatClient(
            @Value("${ai.deepseek.api-key}") String apiKey,
            @Value("${ai.deepseek.base-url}") String baseUrl
    ) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    public String chat(String systemPrompt, List<DeepSeekMessage> conversation) {
        if (systemPrompt == null || systemPrompt.isBlank() || conversation == null || conversation.isEmpty()) {
            throw new IllegalArgumentException("DeepSeek messages must not be empty");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is not configured");
        }

        JSONObject bodyJson = new JSONObject();
        bodyJson.put("model", "deepseek-v4-flash");
        bodyJson.put("stream", false);

        JSONArray messages = new JSONArray();
        messages.add(JSONObject.of("role", "system", "content", systemPrompt));
        for (DeepSeekMessage message : conversation) {
            if (message == null
                    || !Set.of("user", "assistant").contains(message.role())
                    || message.content() == null
                    || message.content().isBlank()) {
                throw new IllegalArgumentException("DeepSeek message is invalid");
            }
            messages.add(JSONObject.of("role", message.role(), "content", message.content()));
        }
        bodyJson.put("messages", messages);

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(
                        bodyJson.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                ))
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException(
                        "Language model request failed: " + response.code() + " - " + responseBody
                );
            }

            JSONObject responseJson = JSONObject.parseObject(responseBody);
            String content = responseJson.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("Language model returned an empty response");
            }
            return content;
        } catch (IOException exception) {
            throw new IllegalStateException("Language model connection failed", exception);
        }
    }
}
