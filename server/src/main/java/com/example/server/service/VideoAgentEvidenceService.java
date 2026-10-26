package com.example.server.service;

import com.example.server.dto.VideoAgentCitation;
import com.example.server.service.context.VideoContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class VideoAgentEvidenceService {

    private static final int MAX_TRANSCRIPT_EVIDENCE = 80;
    private static final int MAX_VISUAL_EVIDENCE = 20;

    private final VideoKnowledgeRetriever retriever;

    public VideoAgentEvidenceService(VideoKnowledgeRetriever retriever) {
        this.retriever = retriever;
    }

    public EvidenceBundle retrieve(
            VideoKnowledgeIndexService.IndexSnapshot index, String query, Runnable beforeEmbedding
    ) {
        List<VideoKnowledgeRetriever.KnowledgeSearchHit> hits =
                retriever.searchMultimodal(index, query, beforeEmbedding);
        VideoContext context = index.context();
        Map<Long, VideoContext.TranscriptEvidence> transcripts = context.transcriptEvidence().stream()
                .collect(Collectors.toMap(VideoContext.TranscriptEvidence::id, Function.identity()));
        Map<Long, VideoContext.VisualEvidence> visuals = context.visualEvidence().stream()
                .collect(Collectors.toMap(VideoContext.VisualEvidence::id, Function.identity()));
        LinkedHashMap<String, VideoAgentCitation> evidence = new LinkedHashMap<>();
        int transcriptCount = 0;
        int visualCount = 0;
        for (VideoKnowledgeRetriever.KnowledgeSearchHit hit : hits) {
            for (Long id : hit.transcriptSegmentIds()) {
                if (transcriptCount >= MAX_TRANSCRIPT_EVIDENCE) break;
                VideoContext.TranscriptEvidence segment = transcripts.get(id);
                if (segment != null) {
                    String ref = "TRANSCRIPT:" + id;
                    if (!evidence.containsKey(ref)) transcriptCount++;
                    evidence.putIfAbsent(ref, new VideoAgentCitation(
                            ref, "TRANSCRIPT", id, segment.startMs(), segment.endMs(), segment.text()
                    ));
                }
            }
            for (Long id : hit.visualEvidenceIds()) {
                if (visualCount >= MAX_VISUAL_EVIDENCE) break;
                VideoContext.VisualEvidence frame = visuals.get(id);
                if (frame != null && frame.ocrText() != null && !frame.ocrText().isBlank()) {
                    String ref = "OCR:" + id;
                    if (!evidence.containsKey(ref)) visualCount++;
                    evidence.putIfAbsent(ref, new VideoAgentCitation(
                            ref, "OCR", id, frame.timestampMs(), frame.timestampMs(), frame.ocrText()
                    ));
                }
            }
        }
        return new EvidenceBundle(hits, List.copyOf(evidence.values()));
    }

    public record EvidenceBundle(
            List<VideoKnowledgeRetriever.KnowledgeSearchHit> hits,
            List<VideoAgentCitation> evidence
    ) {
        public EvidenceBundle {
            hits = List.copyOf(hits);
            evidence = List.copyOf(evidence);
        }
    }
}
