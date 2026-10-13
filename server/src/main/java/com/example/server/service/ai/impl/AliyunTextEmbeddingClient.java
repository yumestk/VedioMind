package com.example.server.service.ai.impl;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class AliyunTextEmbeddingClient {

    public static final String MODEL = "text-embedding-v4";
    public static final int DIMENSION = 1024;
    public static final int MAX_BATCH_SIZE = 10;

    private final String apiKey;
    private final TextEmbedding textEmbedding = new TextEmbedding();

    public AliyunTextEmbeddingClient(@Value("${ai.aliyun.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    public EmbeddingBatch embedDocuments(List<String> texts) {
        return embed(texts, TextEmbeddingParam.TextType.DOCUMENT);
    }

    public List<Double> embedQuery(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding query must not be empty");
        }
        return embed(List.of(text), TextEmbeddingParam.TextType.QUERY).vectors().get(0);
    }

    private EmbeddingBatch embed(List<String> texts, TextEmbeddingParam.TextType textType) {
        if (texts == null || texts.isEmpty() || texts.size() > MAX_BATCH_SIZE
                || texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("Embedding batch must contain 1 to 10 non-empty texts");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ALIYUN_API_KEY is not configured");
        }

        try {
            TextEmbeddingParam parameter = TextEmbeddingParam.builder()
                    .apiKey(apiKey)
                    .model(MODEL)
                    .texts(texts)
                    .textType(textType)
                    .parameter("dimension", DIMENSION)
                    .build();
            TextEmbeddingResult result = textEmbedding.call(parameter);
            TextEmbeddingOutput output = result == null ? null : result.getOutput();
            List<TextEmbeddingResultItem> items = output == null ? null : output.getEmbeddings();
            if (items == null || items.size() != texts.size()) {
                throw new IllegalStateException("Aliyun embedding returned an incomplete batch");
            }

            List<TextEmbeddingResultItem> ordered = new ArrayList<>(items);
            ordered.sort(Comparator.comparingInt(TextEmbeddingResultItem::getTextIndex));
            List<List<Double>> vectors = ordered.stream().map(item -> {
                List<Double> vector = item.getEmbedding();
                if (vector == null || vector.size() != DIMENSION) {
                    throw new IllegalStateException("Aliyun embedding returned an invalid vector dimension");
                }
                return List.copyOf(vector);
            }).toList();
            int totalTokens = result.getUsage() == null || result.getUsage().getTotalTokens() == null
                    ? 0 : result.getUsage().getTotalTokens();
            return new EmbeddingBatch(vectors, totalTokens);
        } catch (Exception exception) {
            throw new IllegalStateException("Aliyun text embedding failed: " + conciseMessage(exception), exception);
        }
    }

    private String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    public record EmbeddingBatch(List<List<Double>> vectors, int totalTokens) {
        public EmbeddingBatch {
            vectors = List.copyOf(vectors);
        }
    }
}
