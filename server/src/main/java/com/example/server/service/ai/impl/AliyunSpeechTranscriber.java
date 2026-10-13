package com.example.server.service.ai.impl;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.service.ai.SpeechTranscriber;
import com.example.server.service.ai.AiProviderException;
import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.AiRetryExecutor;
import com.example.server.service.ai.TranscriptSegmentDraft;
import com.example.server.service.ai.TranscriptionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class AliyunSpeechTranscriber implements SpeechTranscriber {

    static final String MODEL = "paraformer-realtime-v2";
    static final int SAMPLE_RATE = 16_000;

    private final String apiKey;
    private final AiRetryExecutor retryExecutor;

    public AliyunSpeechTranscriber(
            @Value("${ai.aliyun.api-key}") String apiKey,
            AiRetryExecutor retryExecutor
    ) {
        this.apiKey = apiKey;
        this.retryExecutor = retryExecutor;
    }

    @Override
    public TranscriptionResult transcribe(File audioFile) {
        if (audioFile == null || !audioFile.isFile() || audioFile.length() == 0) {
            throw new IllegalArgumentException("PCM audio file does not exist or is empty");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ALIYUN_API_KEY is not configured");
        }

        return retryExecutor.execute(AiRateLimitService.ALIYUN_ASR,
                () -> execute(audioFile));
    }

    private TranscriptionResult execute(File audioFile) {
        try {
            RecognitionParam parameter = RecognitionParam.builder()
                    .apiKey(apiKey)
                    .model(MODEL)
                    .format("pcm")
                    .sampleRate(SAMPLE_RATE)
                    .build();
            String responseJson = new Recognition().call(parameter, audioFile);
            return parseResponse(responseJson);
        } catch (ApiException exception) {
            int statusCode = exception.getStatus() == null ? -1 : exception.getStatus().getStatusCode();
            throw AiProviderException.forStatus(
                    "Aliyun speech recognition failed: " + conciseMessage(exception),
                    exception,
                    statusCode,
                    0
            );
        } catch (RuntimeException exception) {
            throw new AiProviderException(
                    "Aliyun speech recognition failed: " + conciseMessage(exception),
                    exception,
                    false,
                    0
            );
        }
    }

    private String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    TranscriptionResult parseResponse(String responseJson) {
        JSONObject response = JSON.parseObject(responseJson);
        JSONArray sentences = response == null ? null : response.getJSONArray("sentences");
        if (sentences == null || sentences.isEmpty()) {
            throw new IllegalStateException("Aliyun speech recognition returned no sentences");
        }

        List<TranscriptSegmentDraft> segments = new ArrayList<>();
        for (int index = 0; index < sentences.size(); index++) {
            JSONObject sentence = sentences.getJSONObject(index);
            Long startMs = sentence.getLong("begin_time");
            Long endMs = sentence.getLong("end_time");
            String text = sentence.getString("text");
            if (startMs == null || endMs == null || text == null || text.isBlank()) {
                continue;
            }
            segments.add(new TranscriptSegmentDraft(startMs, endMs, text));
        }

        segments.sort(Comparator.comparingLong(TranscriptSegmentDraft::startMs));
        if (segments.isEmpty()) {
            throw new IllegalStateException("Aliyun speech recognition returned no complete sentences");
        }
        return new TranscriptionResult(segments);
    }
}
