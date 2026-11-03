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

    /** Local lexical reranking of subtitle neighborhoods; vectors already selected the semantic chunks. */
    public List<Candidate> rankForQuestion(String question, List<Candidate> candidates) {
        String query = question.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> terms = new LinkedHashSet<>();
        for (int size = 2; size <= 3; size++) {
            for (int i = 0; i + size <= query.length(); i++) terms.add(query.substring(i, i + size));
        }
        List<Candidate> chronological = candidates.stream()
                .sorted(Comparator.comparingLong(value -> value.evidence().startMs())).toList();
        Map<String, Double> scores = new HashMap<>();
        List<String> windows = new ArrayList<>();
        for (int i = 0; i < chronological.size(); i++) {
            String text = questionWindow(chronological, i).stream().map(value -> value.evidence().text())
                    .collect(java.util.stream.Collectors.joining());
            windows.add(text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""));
        }
        Map<String, Long> frequencies = new HashMap<>();
        for (String term : terms) frequencies.put(term, windows.stream().filter(text -> text.contains(term)).count());
        for (int i = 0; i < chronological.size(); i++) {
            double score = 0;
            for (String term : terms) if (windows.get(i).contains(term)) {
                score += term.length() * Math.log(1.0 + windows.size() / (1.0 + frequencies.get(term)));
            }
            scores.put(chronological.get(i).evidence().ref(), score);
        }
        // Stable ties retain the cross-chunk round-robin order from expand().
        return candidates.stream().sorted(Comparator.comparingDouble((Candidate value) ->
                scores.getOrDefault(value.evidence().ref(), 0.0)).reversed()).toList();
    }

    /** Keep the same neighborhood intact when ranking and packing question evidence. */
    static List<Candidate> questionWindow(List<Candidate> chronological, int anchor) {
        Candidate center = chronological.get(anchor);
        if (!"TRANSCRIPT".equals(center.evidence().type())) return List.of(center);
        var subtitles = chronological.stream().filter(v -> "TRANSCRIPT".equals(v.evidence().type())).toList();
        int position = subtitles.indexOf(center);
        long start = center.evidence().startMs();
        return subtitles.subList(Math.max(0, position - 3), Math.min(subtitles.size(), position + 4))
                .stream().filter(value -> Math.abs(value.evidence().startMs() - start) <= 30_000).toList();
    }

    /** Ranked whole neighborhoods; no answer labels or extra model calls are used. */
    public List<List<VideoAgentCitation>> windows(String query, List<Candidate> candidates) {
        var chronological = candidates.stream().sorted(Comparator.comparingLong(v -> v.evidence().startMs())).toList();
        return rankForQuestion(query, candidates).stream().map(anchor -> questionWindow(chronological,
                chronological.indexOf(anchor)).stream().map(Candidate::evidence).toList()).toList();
    }

    /** The exact wire representation is shared by packing and generation. */
    public static String formatEvidence(List<VideoAgentCitation> values) {
        return JSON.toJSONString(values.stream().map(v -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("evidenceRef", v.ref()); item.put("type", v.type());
            item.put("startMs", v.startMs()); item.put("endMs", v.endMs()); item.put("text", v.text());
            return item;
        }).toList());
    }

    /** Fair initial shares, then round-robin reuse of unspent and duplicate evidence space. */
    public List<List<VideoAgentCitation>> allocate(List<List<List<VideoAgentCitation>>> windows, long limit,
            List<java.util.function.Predicate<List<VideoAgentCitation>>> fits) {
        if (windows.size() != fits.size()) throw new IllegalArgumentException("One budget check per task is required");
        List<LinkedHashMap<String, VideoAgentCitation>> selected = new ArrayList<>();
        windows.forEach(v -> selected.add(new LinkedHashMap<>()));
        int rounds = windows.stream().mapToInt(List::size).max().orElse(0);
        long duplicates = 0, nodeOmissions = 0, shareOmissions = 0, poolOmissions = 0;
        for (int phase = 0; phase < 2; phase++) for (int offset = 0; offset < rounds; offset++) {
            for (int task = 0; task < windows.size(); task++) {
                if (offset >= windows.get(task).size()) continue;
                var trial = new LinkedHashMap<>(selected.get(task));
                windows.get(task).get(offset).forEach(v -> trial.putIfAbsent(v.ref(), v));
                if (trial.size() == selected.get(task).size()) { duplicates++; continue; }
                var values = List.copyOf(trial.values());
                if (!fits.get(task).test(values)) { nodeOmissions++; continue; }
                if (phase == 0 && AiPromptBudget.estimateText(formatEvidence(values)) > limit / windows.size()) { shareOmissions++; continue; }
                Map<String, VideoAgentCitation> union = new LinkedHashMap<>();
                for (int i = 0; i < selected.size(); i++) union.putAll(i == task ? trial : selected.get(i));
                if (AiPromptBudget.estimateText(formatEvidence(List.copyOf(union.values()))) <= limit) selected.set(task, trial);
                else poolOmissions++;
            }
        }
        for (int i = 0; i < selected.size(); i++) log.info("Evidence allocation task={} windows={} selected={} refs={}",
                i + 1, windows.get(i).size(), selected.get(i).size(), selected.get(i).keySet());
        log.info("Evidence window attempts duplicates={} nodeBudget={} initialShare={} sharedBudget={}",
                duplicates, nodeOmissions, shareOmissions, poolOmissions);
        return selected.stream().map(v -> List.copyOf(v.values())).toList();
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
