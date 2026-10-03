package com.example.server.service.ai.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.service.ai.ContentSummarizer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class DeepSeekContentSummarizer implements ContentSummarizer {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    private static final String SYSTEM_PROMPT = """
            # Role
            你是一位拥有认知心理学背景的资深信息架构师。你的专长是从杂乱的语音转录文本中提取高价值信息，并进行逻辑重构。

            # Input Context
            用户将提供一段由视频生成的语音识别文本。文本可能包含口语废话、重复、语气词或识别错误。

            # Goals
            请忽略文本中的噪音，对内容进行深度降噪和逻辑精炼，最终输出一份结构清晰、语气专业的分析报告。

            # Constraints
            1. 必须严格遵守下方的输出格式。
            2. 语气保持客观、理性、犀利。
            3. 如果文本内容过短或无意义，直接输出“无法提取有效信息”。
            4. 禁止输出任何开场白或结束语，直接输出 Markdown 内容。

            # Output Format (Markdown)
            ## 核心摘要
            精简概括视频主旨。

            ## 深度洞察
            提取 3-5 个核心观点，每个观点使用三级标题并解释背后的逻辑、动因或启示。

            ## 原始内容精选
            最多引用三句有价值的原话，并修正明显错别字。

            ## 🏷️ 领域标签
            输出 3-5 个标签。
            """;

    private final String apiKey;
    private final String baseUrl;

    public DeepSeekContentSummarizer(
            @Value("${ai.deepseek.api-key}") String apiKey,
            @Value("${ai.deepseek.base-url}") String baseUrl
    ) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public String summarize(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            throw new IllegalArgumentException("Transcript must not be empty");
        }

        JSONObject bodyJson = new JSONObject();
        bodyJson.put("model", "deepseek-v4-flash");
        bodyJson.put("stream", false);

        JSONArray messages = new JSONArray();
        messages.add(JSONObject.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(JSONObject.of("role", "user", "content", transcript));
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
            String summary = responseJson.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException("Language model returned an empty summary");
            }
            return summary;
        } catch (IOException exception) {
            throw new IllegalStateException("Language model connection failed", exception);
        }
    }
}
