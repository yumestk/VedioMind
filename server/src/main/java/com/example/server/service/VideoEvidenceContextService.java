package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.context.VideoContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/** Query-time context only: original chunk boundaries, vectors and source IDs remain unchanged. */
@Service
public class VideoEvidenceContextService {
    private static final Logger log = LoggerFactory.getLogger(VideoEvidenceContextService.class);
    private final int neighborSegments;
    private final long neighborMillis;

    public VideoEvidenceContextService(
            @Value("${ai.context.neighbor-segments:3}") int neighborSegments,
            @Value("${ai.context.neighbor-seconds:30}") long neighborSeconds) {
        if (neighborSegments < 0 || neighborSegments > 100 || neighborSeconds < 0 || neighborSeconds > 300) {
            throw new IllegalArgumentException("Invalid evidence neighborhood bounds");
        }
        this.neighborSegments = neighborSegments;
        this.neighborMillis = neighborSeconds * 1000;
    }

    public List<Candidate> expand(VideoContext context,
                                  List<VideoKnowledgeRetriever.KnowledgeSearchHit> hits,
                                  boolean includeVisual) {
        long startedAt = System.nanoTime();
        if (hits.isEmpty()) return List.of();
        Map<Long, VideoContext.TranscriptEvidence> transcripts = new HashMap<>();
        context.transcriptEvidence().forEach(value -> transcripts.put(value.id(), value));
        Map<Long, VideoContext.VisualEvidence> visuals = new HashMap<>();
        context.visualEvidence().forEach(value -> visuals.put(value.id(), value));
        List<VideoKnowledgeRetriever.KnowledgeSearchHit> ranked = hits.stream()
                .sorted(Comparator.comparingDouble(VideoKnowledgeRetriever.KnowledgeSearchHit::score).reversed()
                        .thenComparingLong(VideoKnowledgeRetriever.KnowledgeSearchHit::startMs)).toList();
        Map<String, Candidate> unique = new LinkedHashMap<>();
        List<List<VideoAgentCitation>> groups = new ArrayList<>();
        for (var hit : ranked) {
            List<VideoAgentCitation> group = new ArrayList<>();
            for (Long id : hit.transcriptSegmentIds()) {
                var value = transcripts.get(id);
                if (value != null && value.startMs() >= hit.startMs() && value.startMs() < hit.endMs()) {
                    group.add(transcript(value));
                }
            }
            if (includeVisual) for (Long id : hit.visualEvidenceIds()) {
                var value = visuals.get(id);
                if (usable(value) && value.timestampMs() >= hit.startMs() && value.timestampMs() < hit.endMs()) {
                    group.add(visual(value));
                }
            }
            group.sort(Comparator.comparingLong(VideoAgentCitation::startMs).thenComparing(VideoAgentCitation::ref));
            groups.add(group);
        }
        // Round-robin across ranked hits prevents one long chunk from taking the entire evidence budget.
        int longest = groups.stream().mapToInt(List::size).max().orElse(0);
        for (int offset = 0; offset < longest; offset++) for (List<VideoAgentCitation> group : groups) {
            if (offset < group.size()) {
                var value = group.get(offset);
                unique.putIfAbsent(value.ref(), new Candidate(value, true, 0));
            }
        }
        List<Range> ranges = new ArrayList<>();
        for (var hit : hits.stream().sorted(Comparator.comparingLong(
                VideoKnowledgeRetriever.KnowledgeSearchHit::startMs)).toList()) {
            if (!ranges.isEmpty() && hit.startMs() <= ranges.getLast().end) {
                Range previous = ranges.removeLast();
                ranges.add(new Range(previous.start, Math.max(previous.end, hit.endMs())));
            } else ranges.add(new Range(hit.startMs(), hit.endMs()));
        }
        List<Candidate> neighbors = new ArrayList<>();
        for (Range range : ranges) {
            context.transcriptEvidence().stream()
                    .filter(value -> value.startMs() < range.start && value.endMs() >= Math.max(0, range.start - neighborMillis))
                    .sorted(Comparator.comparingLong(VideoContext.TranscriptEvidence::startMs).reversed())
                    .limit(neighborSegments)
                    .forEach(value -> neighbors.add(new Candidate(transcript(value), false,
                            Math.max(0, range.start - value.endMs()))));
            context.transcriptEvidence().stream()
                    .filter(value -> value.startMs() >= range.end && value.startMs() <= range.end + neighborMillis)
                    .sorted(Comparator.comparingLong(VideoContext.TranscriptEvidence::startMs))
                    .limit(neighborSegments)
                    .forEach(value -> neighbors.add(new Candidate(transcript(value), false, value.startMs() - range.end)));
            if (includeVisual && neighborSegments > 0) {
                context.visualEvidence().stream().filter(VideoEvidenceContextService::usable)
                        .filter(value -> value.timestampMs() < range.start && value.timestampMs() >= Math.max(0, range.start - neighborMillis))
                        .sorted(Comparator.comparingLong(VideoContext.VisualEvidence::timestampMs).reversed())
                        .limit(neighborSegments)
                        .forEach(value -> neighbors.add(new Candidate(visual(value), false, range.start - value.timestampMs())));
                context.visualEvidence().stream().filter(VideoEvidenceContextService::usable)
                        .filter(value -> value.timestampMs() >= range.end && value.timestampMs() <= range.end + neighborMillis)
                        .sorted(Comparator.comparingLong(VideoContext.VisualEvidence::timestampMs))
                        .limit(neighborSegments)
                        .forEach(value -> neighbors.add(new Candidate(visual(value), false, value.timestampMs() - range.end)));
            }
        }
        neighbors.stream().sorted(Comparator.comparingLong(Candidate::distanceMs)
                        .thenComparing(value -> value.evidence().ref()))
                .forEach(value -> unique.putIfAbsent(value.evidence().ref(), value));
        List<Candidate> result = List.copyOf(unique.values());
        log.info("Evidence expansion media={} version={} ranges={} core={} neighbors={} duplicates={} latencyMs={}",
                context.mediaId(), context.version(), ranges.size(), result.stream().filter(Candidate::core).count(),
                result.stream().filter(value -> !value.core()).count(),
                groups.stream().mapToInt(List::size).sum() + neighbors.size() - result.size(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return result;
    }

    public List<VideoAgentCitation> select(List<Candidate> candidates, long estimatedTokens,
                                          int maxTranscript, int maxVisual) {
        List<VideoAgentCitation> selected = new ArrayList<>();
        long used = 2;
        int transcripts = 0, visuals = 0;
        for (Candidate candidate : candidates) {
            var value = candidate.evidence();
            boolean transcript = "TRANSCRIPT".equals(value.type());
            if (transcript ? transcripts >= maxTranscript : visuals >= maxVisual) continue;
            long cost = AiPromptBudget.estimateText(JSON.toJSONString(value)) + 1;
            if (used + cost > estimatedTokens) continue;
            selected.add(value);
            used += cost;
            if (transcript) transcripts++; else visuals++;
        }
        log.info("Evidence selection candidates={} selected={} omitted={} estimatedEvidenceTokens={}",
                candidates.size(), selected.size(), candidates.size() - selected.size(), used);
        return List.copyOf(selected);
    }

    private static boolean usable(VideoContext.VisualEvidence value) {
        return value != null && "SUCCEEDED".equals(value.ocrStatus())
                && value.ocrText() != null && !value.ocrText().isBlank();
    }
    private static VideoAgentCitation transcript(VideoContext.TranscriptEvidence value) {
        return new VideoAgentCitation("TRANSCRIPT:" + value.id(), "TRANSCRIPT", value.id(),
                value.startMs(), value.endMs(), value.text());
    }
    private static VideoAgentCitation visual(VideoContext.VisualEvidence value) {
        return new VideoAgentCitation("OCR:" + value.id(), "OCR", value.id(),
                value.timestampMs(), value.timestampMs(), value.ocrText());
    }
    public record Candidate(VideoAgentCitation evidence, boolean core, long distanceMs) { }
    private record Range(long start, long end) { }
}
