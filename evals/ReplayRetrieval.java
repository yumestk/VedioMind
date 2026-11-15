package com.example.server.service;

import com.alibaba.fastjson2.*;
import com.example.server.service.ai.impl.AliyunTextEmbeddingClient;
import com.example.server.dto.*;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Offline provider replay: frozen vectors, existing local Qdrant, no provider keys or HTTP. */
public class ReplayRetrieval {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("INPUT_JSON OUTPUT_JSON");
        if (Files.exists(Path.of(args[1]))) throw new IllegalArgumentException("Refusing to overwrite a replay");
        var input = JSON.parseObject(Files.readString(Path.of(args[0])));
        var snapshots = input.getJSONArray("snapshots").toJavaList(VideoKnowledgeIndexService.IndexSnapshot.class);
        var contexts = new VideoEvidenceContextService(3, 30);
        // The application client can create a missing collection. This diagnostic permits only search.
        var qdrant = new QdrantKnowledgeIndexService(null, "video_knowledge_chunks_v1", Duration.ofSeconds(3)) {
            @Override public List<Long> search(Long media, String version, int processor, List<Double> vector, int limit) {
                var filter = JSONObject.of("must", List.of(
                        JSONObject.of("key", "media_id", "match", JSONObject.of("value", media)),
                        JSONObject.of("key", "context_version", "match", JSONObject.of("value", version)),
                        JSONObject.of("key", "processor_version", "match", JSONObject.of("value", processor))));
                var body = JSONObject.of("vector", vector, "filter", filter, "limit", limit);
                var request = HttpRequest.newBuilder(URI.create("http://localhost:6333/collections/video_knowledge_chunks_v1/points/search"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString())).build();
                try (var http = HttpClient.newHttpClient()) {
                    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) throw new IllegalStateException("Local Qdrant search failed");
                    return JSON.parseObject(response.body()).getJSONArray("result").toJavaList(JSONObject.class)
                            .stream().map(v -> v.getLong("id")).toList();
                } catch (Exception e) { throw new IllegalStateException("Replay requires the recorded local index", e); }
            }
            @Override void close() {}
        };
        List<JSONObject> results = new ArrayList<>();
        try {
            for (var query : input.getJSONArray("queries").toJavaList(JSONObject.class)) {
                String text = query.getString("query");
                var vector = query.getJSONArray("vector").toJavaList(Double.class);
                var embedding = new AliyunTextEmbeddingClient("unused", "http://invalid", null) {
                    @Override public List<Double> embedQuery(String q, Runnable before) {
                        if (!text.equals(q)) throw new IllegalStateException("Unrecorded query");
                        return vector;
                    }
                };
                var retriever = new VideoKnowledgeRetriever(null, embedding, qdrant);
                var hits = retriever.searchMaterials(snapshots, text, () -> {});
                List<JSONObject> materials = new ArrayList<>();
                for (int i = 0; i < snapshots.size(); i++) {
                    var expanded = contexts.expand(snapshots.get(i).context(), hits.get(i), true);
                    var ranked = contexts.rankForQuestion(text, expanded);
                    materials.add(JSONObject.of("materialId", snapshots.get(i).context().mediaId(),
                            "hits", hits.get(i), "expanded", expanded, "ranked", ranked,
                            "leads", contexts.searchLeads(snapshots.get(i).context(), hits.get(i), text)));
                }
                results.add(JSONObject.of("query", text, "materials", materials));
            }
        } finally { qdrant.close(); }
        var originals = new ArrayList<VideoEvidenceContextService.Candidate>();
        for (var index : snapshots) {
            var c = index.context();
            for (var t : c.transcriptEvidence()) originals.add(new VideoEvidenceContextService.Candidate(
                    new VideoAgentCitation(c.mediaId()+":TRANSCRIPT:"+t.id()+":"+c.version(), "TRANSCRIPT", t.id(),
                            t.startMs(), t.endMs(), t.text(), c.mediaId(), c.version()), true, 0));
            for (var v : c.visualEvidence()) originals.add(new VideoEvidenceContextService.Candidate(
                    new VideoAgentCitation(c.mediaId()+":OCR:"+v.id()+":"+c.version(), "OCR", v.id(),
                            v.timestampMs(), v.timestampMs(), v.ocrText(), c.mediaId(), c.version()), true, 0));
        }
        var reader = new StudyEvidenceService(null, null, null, null, null) {
            @Override public List<VideoEvidenceContextService.Candidate> full(PreparedStudyTask task) { return originals; }
        };
        List<JSONObject> reads = new ArrayList<>();
        for (var refs : input.getJSONArray("reads")) {
            var anchors = ((JSONArray) refs).toJavaList(String.class);
            try { reads.add(JSONObject.of("anchors", anchors, "returned", reader.read(null, anchors))); }
            catch (RuntimeException e) {
                reads.add(JSONObject.of("anchors", anchors, "error", e.getMessage()));
                for (var anchor : anchors) {
                    try { reads.add(JSONObject.of("anchors", List.of(anchor), "returned", reader.read(null, List.of(anchor)))); }
                    catch (RuntimeException single) { reads.add(JSONObject.of("anchors", List.of(anchor), "error", single.getMessage())); }
                }
            }
        }
        var result = JSONObject.of("retrieval", results, "reads", reads,
                "resources", StudyAgentSpec.snapshot(), "tools", StudyAgentSpec.tools(),
                "systemPrompt", StudyAgentSpec.systemPrompt());
        result.put("courseNotesSkill", StudyAgentSkills.bundled().read("course-notes"));
        Files.writeString(Path.of(args[1]), JSON.toJSONString(result, JSONWriter.Feature.PrettyFormat), StandardOpenOption.CREATE_NEW);
    }
}
