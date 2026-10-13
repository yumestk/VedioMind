package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.entity.VideoKnowledgeChunk;
import com.example.server.mapper.VideoKnowledgeChunkMapper;
import com.example.server.service.ai.impl.AliyunTextEmbeddingClient;
import com.example.server.service.context.VideoContext;
import com.example.server.service.context.VideoContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class VideoKnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(VideoKnowledgeIndexService.class);
    static final long CHUNK_MS = 5 * 60 * 1_000L;
    static final int PROCESSOR_VERSION = 1;
    private static final String SUMMARIZED = "SUMMARIZED";
    private static final String READY = "READY";
    private static final int MAX_EMBEDDING_TEXT_CHARS = 6_000;

    private final VideoKnowledgeChunkMapper chunkMapper;
    private final VideoContextService contextService;
    private final VideoChunkSummarizer chunkSummarizer;
    private final AliyunTextEmbeddingClient embeddingClient;

    public VideoKnowledgeIndexService(
            VideoKnowledgeChunkMapper chunkMapper,
            VideoContextService contextService,
            VideoChunkSummarizer chunkSummarizer,
            AliyunTextEmbeddingClient embeddingClient
    ) {
        this.chunkMapper = chunkMapper;
        this.contextService = contextService;
        this.chunkSummarizer = chunkSummarizer;
        this.embeddingClient = embeddingClient;
    }

    public IndexSnapshot build(Long mediaId) {
        VideoContext context = contextService.build(mediaId);
        if (!"AVAILABLE".equals(context.transcript().state())) {
            throw new IllegalStateException("Transcript must be available before building the knowledge index");
        }
        List<VideoChunkSummarizer.ChunkInput> plans = plans(context);
        if (plans.isEmpty()) {
            throw new IllegalStateException("VideoContext contains no indexable evidence");
        }

        Map<Integer, VideoKnowledgeChunk> existing = currentChunks(mediaId, context.version()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        VideoKnowledgeChunk::getChunkIndex,
                        chunk -> chunk,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        int generatedSummaries = 0;
        for (VideoChunkSummarizer.ChunkInput plan : plans) {
            VideoKnowledgeChunk chunk = existing.get(plan.chunkIndex());
            if (chunk != null && (SUMMARIZED.equals(chunk.getStatus()) || READY.equals(chunk.getStatus()))) {
                continue;
            }
            VideoChunkSummarizer.ChunkSummary summary = chunkSummarizer.summarize(plan);
            chunk = saveSummary(mediaId, context.version(), plan, summary, chunk);
            existing.put(plan.chunkIndex(), chunk);
            generatedSummaries++;
        }

        Map<Integer, VideoChunkSummarizer.ChunkInput> plansByIndex = plans.stream()
                .collect(java.util.stream.Collectors.toMap(
                        VideoChunkSummarizer.ChunkInput::chunkIndex,
                        plan -> plan
                ));
        List<VideoKnowledgeChunk> pendingEmbeddings = currentChunks(mediaId, context.version()).stream()
                .filter(chunk -> SUMMARIZED.equals(chunk.getStatus()))
                .sorted(Comparator.comparingInt(VideoKnowledgeChunk::getChunkIndex))
                .toList();
        int embeddingTokens = 0;
        for (int offset = 0; offset < pendingEmbeddings.size();
             offset += AliyunTextEmbeddingClient.MAX_BATCH_SIZE) {
            List<VideoKnowledgeChunk> batch = pendingEmbeddings.subList(
                    offset,
                    Math.min(offset + AliyunTextEmbeddingClient.MAX_BATCH_SIZE, pendingEmbeddings.size())
            );
            List<String> texts = batch.stream()
                    .map(chunk -> embeddingText(chunk, plansByIndex.get(chunk.getChunkIndex())))
                    .toList();
            AliyunTextEmbeddingClient.EmbeddingBatch embeddings = embeddingClient.embedDocuments(texts);
            embeddingTokens += embeddings.totalTokens();
            for (int index = 0; index < batch.size(); index++) {
                VideoKnowledgeChunk chunk = batch.get(index);
                chunk.setEmbeddingJson(JSON.toJSONString(embeddings.vectors().get(index)));
                chunk.setStatus(READY);
                chunk.setUpdatedAt(LocalDateTime.now());
                chunkMapper.updateById(chunk);
            }
        }

        List<VideoKnowledgeChunk> ready = currentChunks(mediaId, context.version()).stream()
                .filter(chunk -> READY.equals(chunk.getStatus()))
                .sorted(Comparator.comparingInt(VideoKnowledgeChunk::getChunkIndex))
                .toList();
        if (ready.size() != plans.size()) {
            throw new IllegalStateException("Knowledge index is incomplete");
        }
        cleanupStale(mediaId, context.version());
        log.info("Knowledge index ready for media {}: {} chunks, {} new summaries, {} embedding tokens",
                mediaId, ready.size(), generatedSummaries, embeddingTokens);
        return new IndexSnapshot(context, ready, summaryMaterial(ready));
    }

    public IndexSnapshot requireReady(Long mediaId) {
        VideoContext context = contextService.build(mediaId);
        List<VideoChunkSummarizer.ChunkInput> plans = plans(context);
        List<VideoKnowledgeChunk> ready = currentChunks(mediaId, context.version()).stream()
                .filter(chunk -> READY.equals(chunk.getStatus()))
                .sorted(Comparator.comparingInt(VideoKnowledgeChunk::getChunkIndex))
                .toList();
        if (plans.isEmpty() || ready.size() != plans.size()) {
            throw new IllegalStateException("Knowledge index is not ready for the current VideoContext");
        }
        return new IndexSnapshot(context, ready, summaryMaterial(ready));
    }

    List<VideoChunkSummarizer.ChunkInput> plans(VideoContext context) {
        Map<Integer, MutableChunk> chunks = new TreeMap<>();
        for (VideoContext.TranscriptEvidence transcript : context.transcriptEvidence()) {
            int chunkIndex = Math.toIntExact(transcript.startMs() / CHUNK_MS);
            chunks.computeIfAbsent(chunkIndex, MutableChunk::new).transcripts.add(transcript);
        }
        for (VideoContext.VisualEvidence visual : context.visualEvidence()) {
            if (visual.ocrText() == null || visual.ocrText().isBlank()) {
                continue;
            }
            int chunkIndex = Math.toIntExact(visual.timestampMs() / CHUNK_MS);
            chunks.computeIfAbsent(chunkIndex, MutableChunk::new).visuals.add(visual);
        }
        return chunks.values().stream().map(chunk -> new VideoChunkSummarizer.ChunkInput(
                chunk.index,
                chunk.index * CHUNK_MS,
                (chunk.index + 1L) * CHUNK_MS,
                chunk.transcripts,
                chunk.visuals
        )).toList();
    }

    private VideoKnowledgeChunk saveSummary(
            Long mediaId,
            String contextVersion,
            VideoChunkSummarizer.ChunkInput plan,
            VideoChunkSummarizer.ChunkSummary summary,
            VideoKnowledgeChunk chunk
    ) {
        LocalDateTime now = LocalDateTime.now();
        VideoKnowledgeChunk target = chunk == null ? new VideoKnowledgeChunk() : chunk;
        target.setMediaId(mediaId);
        target.setContextVersion(contextVersion);
        target.setProcessorVersion(PROCESSOR_VERSION);
        target.setChunkIndex(plan.chunkIndex());
        target.setStartMs(plan.startMs());
        target.setEndMs(plan.endMs());
        target.setSummary(summary.summary());
        target.setKeywordsJson(JSON.toJSONString(summary.keywords()));
        target.setEmbeddingJson(null);
        target.setStatus(SUMMARIZED);
        target.setUpdatedAt(now);
        if (target.getId() == null) {
            target.setCreatedAt(now);
            chunkMapper.insert(target);
        } else {
            chunkMapper.updateById(target);
        }
        return target;
    }

    private List<VideoKnowledgeChunk> currentChunks(Long mediaId, String contextVersion) {
        return chunkMapper.selectList(new LambdaQueryWrapper<VideoKnowledgeChunk>()
                .eq(VideoKnowledgeChunk::getMediaId, mediaId)
                .eq(VideoKnowledgeChunk::getContextVersion, contextVersion)
                .eq(VideoKnowledgeChunk::getProcessorVersion, PROCESSOR_VERSION)
                .orderByAsc(VideoKnowledgeChunk::getChunkIndex));
    }

    private String embeddingText(
            VideoKnowledgeChunk chunk,
            VideoChunkSummarizer.ChunkInput input
    ) {
        if (input == null) {
            throw new IllegalStateException("Knowledge chunk no longer matches the current VideoContext");
        }
        StringBuilder text = new StringBuilder(chunk.getSummary())
                .append("\n关键词：")
                .append(String.join(" ", JSON.parseArray(chunk.getKeywordsJson(), String.class)))
                .append("\n字幕：");
        input.transcripts().forEach(segment -> text.append(segment.text()).append(' '));
        if (!input.visuals().isEmpty()) {
            text.append("\n画面文字：");
            input.visuals().forEach(frame -> text.append(frame.ocrText()).append(' '));
        }
        return text.length() <= MAX_EMBEDDING_TEXT_CHARS
                ? text.toString()
                : text.substring(0, MAX_EMBEDDING_TEXT_CHARS);
    }

    private String summaryMaterial(List<VideoKnowledgeChunk> chunks) {
        StringBuilder material = new StringBuilder();
        for (VideoKnowledgeChunk chunk : chunks) {
            material.append('[')
                    .append(formatTime(chunk.getStartMs()))
                    .append('-')
                    .append(formatTime(chunk.getEndMs()))
                    .append("] ")
                    .append(chunk.getSummary())
                    .append("\n关键词：")
                    .append(String.join(" ", JSON.parseArray(chunk.getKeywordsJson(), String.class)))
                    .append("\n\n");
        }
        return material.toString().trim();
    }

    private void cleanupStale(Long mediaId, String contextVersion) {
        chunkMapper.delete(new LambdaQueryWrapper<VideoKnowledgeChunk>()
                .eq(VideoKnowledgeChunk::getMediaId, mediaId)
                .and(wrapper -> wrapper
                        .ne(VideoKnowledgeChunk::getContextVersion, contextVersion)
                        .or()
                        .ne(VideoKnowledgeChunk::getProcessorVersion, PROCESSOR_VERSION)));
    }

    private String formatTime(long milliseconds) {
        long totalSeconds = Math.max(0, milliseconds) / 1_000;
        return "%02d:%02d:%02d".formatted(
                totalSeconds / 3_600,
                totalSeconds % 3_600 / 60,
                totalSeconds % 60
        );
    }

    public record IndexSnapshot(
            VideoContext context,
            List<VideoKnowledgeChunk> chunks,
            String summaryMaterial
    ) {
        public IndexSnapshot {
            chunks = List.copyOf(chunks);
        }
    }

    private static final class MutableChunk {
        private final int index;
        private final List<VideoContext.TranscriptEvidence> transcripts = new ArrayList<>();
        private final List<VideoContext.VisualEvidence> visuals = new ArrayList<>();

        private MutableChunk(int index) {
            this.index = index;
        }
    }
}
