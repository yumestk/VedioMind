package com.example.server.service;

import com.example.server.service.ai.AiRateLimitService.AiRateLimitExceededException;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RedissonClient;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.entity.VideoKnowledgeChunk;
import com.example.server.entity.VideoChapter;
import com.example.server.mapper.VideoChapterMapper;
import com.example.server.service.ai.AiCallLedger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    static final int FINE_VERSION = 2;
    static final long MAX_CHUNK_MS = 120_000;
    private static final String SUMMARIZED = "SUMMARIZED";
    private static final String READY = "READY";
    private static final int MAX_EMBEDDING_TEXT_CHARS = 6_000;

    private final RedissonClient redisson;
    private final VideoChapterMapper chapterMapper;
    private final long fineIndexMediaId;
    private final VideoKnowledgeChunkMapper chunkMapper;
    private final VideoContextService contextService;
    private final VideoChunkSummarizer chunkSummarizer;
    private final AliyunTextEmbeddingClient embeddingClient;
    private final QdrantKnowledgeIndexService qdrantIndexService;

    public VideoKnowledgeIndexService(
            VideoKnowledgeChunkMapper chunkMapper,
            VideoContextService contextService,
            VideoChunkSummarizer chunkSummarizer,
            AliyunTextEmbeddingClient embeddingClient,
            QdrantKnowledgeIndexService qdrantIndexService, RedissonClient redisson
    ) {
        this(chunkMapper, contextService, chunkSummarizer, embeddingClient, qdrantIndexService, redisson, null, 0);
    }

    @Autowired
    public VideoKnowledgeIndexService(
            VideoKnowledgeChunkMapper chunkMapper, VideoContextService contextService,
            VideoChunkSummarizer chunkSummarizer, AliyunTextEmbeddingClient embeddingClient,
            QdrantKnowledgeIndexService qdrantIndexService, RedissonClient redisson,
            VideoChapterMapper chapterMapper, @Value("${ai.index.v2-media-id:0}") long fineIndexMediaId) {
        this.chapterMapper = chapterMapper;
        this.fineIndexMediaId = fineIndexMediaId;
        this.redisson = redisson;
        this.chunkMapper = chunkMapper;
        this.contextService = contextService;
        this.chunkSummarizer = chunkSummarizer;
        this.embeddingClient = embeddingClient;
        this.qdrantIndexService = qdrantIndexService;
    }

    public IndexSnapshot build(Long mediaId) { return build(mediaId, null); }

    public IndexSnapshot build(Long mediaId, String expectedVersion) {
        return build(mediaId, expectedVersion, selectedProcessor(mediaId), null);
    }

    IndexSnapshot build(Long mediaId, String expectedVersion, int processor, String expectedFingerprint) {
        var lock = redisson.getLock("lock:knowledge-build:" + mediaId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) throw new AiRateLimitExceededException(
                    "视频问答资料正在由其他任务准备", 5);
            VideoContext context = contextService.build(mediaId);
            if (expectedVersion != null && !expectedVersion.equals(context.version())) {
                throw new IllegalArgumentException("字幕或画面证据已更新，请重新准备问答资料");
            }
            var plan = indexPlan(context, processor);
            if (expectedFingerprint != null && !expectedFingerprint.equals(plan.version())) {
                throw new IllegalArgumentException("冻结的知识块计划已变化");
            }
            return buildCurrent(context, plan);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while locking knowledge index", exception);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void requireCurrent(VideoContext context, IndexPlan plan) {
        if (!context.version().equals(contextService.currentVersion(context.mediaId()))
                || (plan.processor() == FINE_VERSION && !plan.version().equals(indexPlan(context, FINE_VERSION).version()))) {
            throw new IllegalArgumentException("字幕或画面证据已更新，请重新准备问答资料");
        }
    }

    private IndexSnapshot buildCurrent(VideoContext context, IndexPlan indexPlan) {
        Long mediaId = context.mediaId();
        if (!"AVAILABLE".equals(context.transcript().state())) {
            throw new IllegalStateException("Transcript must be available before building the knowledge index");
        }
        List<VideoChunkSummarizer.ChunkInput> plans = indexPlan.chunks();
        if (plans.isEmpty()) {
            throw new IllegalStateException("VideoContext contains no indexable evidence");
        }

        Map<Integer, VideoKnowledgeChunk> existing = currentChunks(mediaId, indexPlan).stream()
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
            requireCurrent(context, indexPlan);
            VideoChunkSummarizer.ChunkSummary summary = chunkSummarizer.summarize(plan);
            requireCurrent(context, indexPlan);
            chunk = saveSummary(mediaId, indexPlan, plan, summary, chunk);
            existing.put(plan.chunkIndex(), chunk);
            generatedSummaries++;
        }

        Map<Integer, VideoChunkSummarizer.ChunkInput> plansByIndex = plans.stream()
                .collect(java.util.stream.Collectors.toMap(
                        VideoChunkSummarizer.ChunkInput::chunkIndex,
                        plan -> plan
                ));
        List<VideoKnowledgeChunk> pendingEmbeddings = currentChunks(mediaId, indexPlan).stream()
                .filter(chunk -> SUMMARIZED.equals(chunk.getStatus()))
                .sorted(Comparator.comparingInt(VideoKnowledgeChunk::getChunkIndex))
                .toList();
        int embeddingTokens = 0;
        for (int offset = 0; offset < pendingEmbeddings.size();
             offset += AliyunTextEmbeddingClient.MAX_BATCH_SIZE) {
            requireCurrent(context, indexPlan);
            List<VideoKnowledgeChunk> batch = pendingEmbeddings.subList(
                    offset,
                    Math.min(offset + AliyunTextEmbeddingClient.MAX_BATCH_SIZE, pendingEmbeddings.size())
            );
            List<String> texts = batch.stream()
                    .map(chunk -> embeddingText(chunk, plansByIndex.get(chunk.getChunkIndex())))
                    .toList();
            AliyunTextEmbeddingClient.EmbeddingBatch embeddings = embeddingClient.embedDocuments(texts);
            requireCurrent(context, indexPlan);
            embeddingTokens += embeddings.totalTokens();
            for (int index = 0; index < batch.size(); index++) {
                VideoKnowledgeChunk chunk = batch.get(index);
                chunk.setEmbeddingJson(JSON.toJSONString(embeddings.vectors().get(index)));
                chunk.setStatus(READY);
                chunk.setUpdatedAt(LocalDateTime.now());
                chunkMapper.updateById(chunk);
            }
        }

        List<VideoKnowledgeChunk> ready = currentChunks(mediaId, indexPlan).stream()
                .filter(chunk -> READY.equals(chunk.getStatus()))
                .sorted(Comparator.comparingInt(VideoKnowledgeChunk::getChunkIndex))
                .toList();
        if (!matchesPlan(ready, plans)) {
            throw new IllegalStateException("Knowledge index is incomplete");
        }
        requireCurrent(context, indexPlan);
        try {
            qdrantIndexService.replaceMediaIndex(ready);
        } catch (QdrantKnowledgeIndexService.QdrantUnavailableException exception) {
            if (indexPlan.processor() == FINE_VERSION) throw exception;
            log.warn("Qdrant index unavailable for media {}; MySQL index remains usable: {}",
                    mediaId, exception.getMessage());
        }
        requireCurrent(context, indexPlan);
        log.info("Knowledge index ready for media {}: {} chunks, {} new summaries, {} embedding tokens",
                mediaId, ready.size(), generatedSummaries, embeddingTokens);
        return new IndexSnapshot(context, ready, summaryMaterial(ready));
    }

    public IndexSnapshot requireReady(Long mediaId) {
        int processor = selectedProcessor(mediaId);
        try {
            return requireReady(mediaId, processor);
        } catch (KnowledgeIndexNotReadyException notReady) {
            if (processor == PROCESSOR_VERSION) throw notReady;
            return requireReady(mediaId, PROCESSOR_VERSION);
        }
    }

    IndexSnapshot requireReady(Long mediaId, int processor) {
        VideoContext context = contextService.build(mediaId);
        var plan = indexPlan(context, processor);
        List<VideoChunkSummarizer.ChunkInput> plans = plan.chunks();
        List<VideoKnowledgeChunk> ready = currentChunks(mediaId, plan).stream()
                .filter(chunk -> READY.equals(chunk.getStatus()))
                .sorted(Comparator.comparingInt(VideoKnowledgeChunk::getChunkIndex))
                .toList();
        if (plans.isEmpty() || !matchesPlan(ready, plans)) {
            throw new KnowledgeIndexNotReadyException(
                    "Knowledge index is not ready for the current VideoContext"
            );
        }
        return new IndexSnapshot(context, ready, summaryMaterial(ready));
    }

    private int selectedProcessor(long mediaId) {
        return mediaId == fineIndexMediaId ? FINE_VERSION : PROCESSOR_VERSION;
    }

    private static boolean matchesPlan(List<VideoKnowledgeChunk> ready, List<VideoChunkSummarizer.ChunkInput> plans) {
        if (ready.size() != plans.size()) return false;
        for (int i = 0; i < plans.size(); i++) {
            var chunk = ready.get(i);
            var plan = plans.get(i);
            if (chunk.getChunkIndex() != plan.chunkIndex()
                    || chunk.getStartMs() != plan.startMs() || chunk.getEndMs() != plan.endMs()) return false;
        }
        return true;
    }

    IndexPlan indexPlan(VideoContext context, int processor) {
        if (processor == PROCESSOR_VERSION) return new IndexPlan(context.version(), processor, plans(context));
        if (processor != FINE_VERSION) throw new IllegalArgumentException("Unknown knowledge processor " + processor);
        var chapters = chapterMapper.selectList(new LambdaQueryWrapper<VideoChapter>()
                .eq(VideoChapter::getMediaId, context.mediaId()).orderByAsc(VideoChapter::getStartMs));
        var chunks = finePlans(context, chapters);
        var boundaries = chapters.stream().map(c -> List.of(c.getStartMs(), c.getEndMs())).toList();
        var ranges = chunks.stream().map(c -> List.of(c.startMs(), c.endMs())).toList();
        var fingerprint = AiCallLedger.sha256(JSON.toJSONString(List.of(context.version(), boundaries, ranges)));
        return new IndexPlan(fingerprint, processor, chunks);
    }

    static List<VideoChunkSummarizer.ChunkInput> finePlans(VideoContext context, List<VideoChapter> chapters) {
        var captions = context.transcriptEvidence();
        var ocr = context.visualEvidence().stream().filter(v -> v.ocrText() != null && !v.ocrText().isBlank()).toList();
        long end = Math.max(captions.stream().mapToLong(VideoContext.TranscriptEvidence::endMs).max().orElse(0),
                ocr.stream().mapToLong(v -> v.timestampMs() + 1).max().orElse(0));
        if (end <= 0) return List.of();
        List<Long> boundaries = new ArrayList<>();
        boundaries.add(0L);
        if (chapters.isEmpty()) {
            for (long start = MAX_CHUNK_MS; start < end; start += MAX_CHUNK_MS) boundaries.add(start);
        } else {
            var starts = chapters.stream().map(VideoChapter::getStartMs).filter(t -> 0 <= t && t < end)
                    .distinct().sorted().skip(1).toList();
            boundaries.addAll(starts);
        }
        boundaries.add(end);
        var plans = new ArrayList<VideoChunkSummarizer.ChunkInput>();
        int chunkIndex = 0;
        for (int i = 1; i < boundaries.size(); i++) {
            long start = boundaries.get(i - 1);
            long stop = boundaries.get(i);
            long count = Math.ceilDiv(stop - start, MAX_CHUNK_MS);
            for (long remaining = count; remaining > 0; remaining--) {
                long target = start + Math.ceilDiv(stop - start, remaining);
                long cut = remaining == 1 ? stop : captionBoundary(captions, target,
                        Math.max(start + 1, stop - (remaining - 1) * MAX_CHUNK_MS),
                        Math.min(start + MAX_CHUNK_MS, stop - remaining + 1));
                final long from = start;
                var texts = captions.stream().filter(t -> from <= t.startMs() && t.startMs() < cut).toList();
                var frames = ocr.stream().filter(v -> from <= v.timestampMs() && v.timestampMs() < cut).toList();
                if (!texts.isEmpty() || !frames.isEmpty()) {
                    plans.add(new VideoChunkSummarizer.ChunkInput(chunkIndex, from, cut, texts, frames));
                }
                chunkIndex++;
                start = cut;
            }
        }
        return List.copyOf(plans);
    }

    private static long captionBoundary(List<VideoContext.TranscriptEvidence> captions, long target, long min, long max) {
        return captions.stream().map(VideoContext.TranscriptEvidence::startMs)
                .filter(t -> min <= t && t <= max && Math.abs(t - target) <= 4_000)
                .min(Comparator.comparingLong(t -> Math.abs(t - target))).orElse(target);
    }

    record IndexPlan(String version, int processor, List<VideoChunkSummarizer.ChunkInput> chunks) {}

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
            IndexPlan indexPlan,
            VideoChunkSummarizer.ChunkInput plan,
            VideoChunkSummarizer.ChunkSummary summary,
            VideoKnowledgeChunk chunk
    ) {
        LocalDateTime now = LocalDateTime.now();
        VideoKnowledgeChunk target = chunk == null ? new VideoKnowledgeChunk() : chunk;
        target.setMediaId(mediaId);
        target.setContextVersion(indexPlan.version());
        target.setProcessorVersion(indexPlan.processor());
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

    private List<VideoKnowledgeChunk> currentChunks(Long mediaId, IndexPlan plan) {
        return chunkMapper.selectList(new LambdaQueryWrapper<VideoKnowledgeChunk>()
                .eq(VideoKnowledgeChunk::getMediaId, mediaId)
                .eq(VideoKnowledgeChunk::getContextVersion, plan.version())
                .eq(VideoKnowledgeChunk::getProcessorVersion, plan.processor())
                .orderByAsc(VideoKnowledgeChunk::getChunkIndex));
    }

    private String embeddingText(
            VideoKnowledgeChunk chunk,
            VideoChunkSummarizer.ChunkInput input
    ) {
        if (input == null) {
            throw new IllegalStateException("Knowledge chunk no longer matches the current VideoContext");
        }
        String summary = chunk.getSummary() + "\n关键词："
                + String.join(" ", JSON.parseArray(chunk.getKeywordsJson(), String.class));
        String subtitles = input.transcripts().stream().map(VideoContext.TranscriptEvidence::text)
                .collect(java.util.stream.Collectors.joining(" "));
        String visuals = input.visuals().stream().map(VideoContext.VisualEvidence::ocrText)
                .collect(java.util.stream.Collectors.joining(" "));
        if (chunk.getProcessorVersion() == FINE_VERSION) {
            return clip(summary, 1_000) + "\n字幕：" + clip(subtitles, 2_400)
                    + "\n画面文字：" + clip(visuals, 2_500);
        }
        return clip(summary + "\n字幕：" + subtitles + (input.transcripts().isEmpty() ? "" : " ")
                + (input.visuals().isEmpty() ? "" : "\n画面文字：" + visuals + " "), MAX_EMBEDDING_TEXT_CHARS);
    }

    private static String clip(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end);
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

        public String indexVersion() {
            return chunks.isEmpty() ? context.version() : chunks.getFirst().getContextVersion();
        }

        public int processorVersion() {
            return chunks.isEmpty() || chunks.getFirst().getProcessorVersion() == null
                    ? PROCESSOR_VERSION : chunks.getFirst().getProcessorVersion();
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

    public static class KnowledgeIndexNotReadyException extends RuntimeException {
        public KnowledgeIndexNotReadyException(String message) {
            super(message);
        }
    }
}
