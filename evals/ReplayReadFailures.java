package com.example.server.service;

import com.alibaba.fastjson2.*;
import com.example.server.dto.*;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.ai.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;

/** Zero-provider counterfactual checks of frozen observations, not a new Agent Trial or grader. */
public class ReplayReadFailures {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("EVIDENCE_JSON FROZEN_RUN_DIR NEW_OUTPUT_JSON");
        if (Files.exists(Path.of(args[2]))) throw new IllegalArgumentException("Refusing to overwrite evidence");
        var originals = new ArrayList<VideoEvidenceContextService.Candidate>();
        for (var e : JSON.parseArray(Files.readString(Path.of(args[0]))).toList(JSONObject.class)) {
            long id = e.getLongValue("id"), media = e.getLongValue("mediaId");
            originals.add(new VideoEvidenceContextService.Candidate(new VideoAgentCitation(e.getString("ref"),
                    e.getString("type"), id, e.getLongValue("startMs"), e.getLongValue("endMs"),
                    e.getString("text"), media, "t1-v1-p2"), true, 0));
        }
        var reader = new StudyEvidenceService(null, null, null, null, null) {
            @Override public List<VideoEvidenceContextService.Candidate> full(PreparedStudyTask task) { return originals; }
        };
        var reads = new ArrayList<JSONObject>();
        var projections = new ArrayList<JSONObject>();
        JSONObject budget = null;
        for (String name : List.of("N01", "N02", "N03")) {
            var dir = Path.of(args[1], name);
            var steps = JSON.parseArray(Files.readString(dir.resolve("video_agent_steps.json"))).toList(JSONObject.class);
            steps.sort(Comparator.comparingInt((JSONObject s) -> s.getIntValue("round_no")).thenComparing(s -> s.getString("stage")));
            List<JSONObject> history = new ArrayList<>();
            var runJson = JSON.parseArray(Files.readString(dir.resolve("video_agent_runs.json"))).getJSONObject(0);
            history.add(JSONObject.of("role", "system", "content", StudyAgentSpec.systemPrompt()));
            history.add(JSONObject.of("role", "user", "content", "宿主冻结任务：" + runJson.getString("prepared_task")));
            Set<String> discovered = new LinkedHashSet<>();
            for (var step : steps) {
                String stage = step.getString("stage");
                if (!"AGENT_DECISION".equals(stage)) continue;
                var decision = JSON.parseObject(step.getString("output_json"));
                if (name.equals("N02") && step.getIntValue("round_no") > 2) continue;
                history.add(decision);
                var calls = decision.getJSONArray("tool_calls");
                for (int i = 0; i < calls.size(); i++) {
                    var call = calls.getJSONObject(i);
                    var function = call.getJSONObject("function");
                    var input = JSON.parseObject(function.getString("arguments"));
                    if ("read_evidence".equals(function.getString("name"))) {
                        var window = reader.read(null, input.getList("refs", String.class));
                        long estimated = AiPromptBudget.estimateText(JSON.toJSONString(window.evidence()));
                        if (estimated > 14000 || window.evidence().isEmpty()) throw new AssertionError("Read bound");
                        reads.add(JSONObject.of("case", name, "round", step.getIntValue("round_no"),
                                "anchors", input.getList("refs", String.class), "result", window, "estimatedInput", estimated));
                    }
                    if (name.equals("N01") && "save_note".equals(function.getString("name"))) {
                        var projected = StudyArtifactService.noteFromPoints(input.getJSONObject("note"));
                        if (projected.getJSONArray("gaps").isEmpty()) throw new AssertionError("Partial gap lost");
                        projections.add(JSONObject.of("case", name, "round", step.getIntValue("round_no"), "note", projected));
                    }
                    int toolIndex = i;
                    var observation = steps.stream().filter(s -> s.getIntValue("round_no") == step.getIntValue("round_no")
                            && ("AGENT_TOOL_" + toolIndex).equals(s.getString("stage"))).findFirst().orElseThrow();
                    var output = JSON.parseObject(observation.getString("output_json"));
                    if (output.containsKey("hits")) for (var hit : output.getJSONArray("hits").toList(JSONObject.class)) discovered.add(hit.getString("ref"));
                    history.add(JSONObject.of("role", "tool", "tool_call_id", call.getString("id"),
                            "content", AiCallLedger.canonicalJson(output)));
                }
            }
            if (name.equals("N02")) {
                var runs = mock(VideoAgentRunService.class);
                var run = new VideoAgentRun();
                run.setAccountedTokens(runJson.getLongValue("accounted_tokens"));
                run.setGenerationCalls(runJson.getIntValue("generation_calls"));
                run.setRetrievalCalls(runJson.getIntValue("retrieval_calls"));
                when(runs.getRequired("replay")).thenReturn(run);
                when(runs.requestDeadline("replay")).thenReturn(Instant.now().plusSeconds(110));
                var runtime = new StudyAgentRuntime(runs, null, null, null, new AiPromptBudget(64000, 8192));
                var method = StudyAgentRuntime.class.getDeclaredMethod("decisionBudget", String.class, int.class, int.class,
                        Set.class, Set.class, boolean.class, List.class);
                method.setAccessible(true);
                var task = JSON.parseObject(runJson.getString("prepared_task"), PreparedStudyTask.class);
                try (var ignored = StudyExecutionContext.open(task, "replay", 1)) {
                    budget = (JSONObject) method.invoke(runtime, "replay", 3, 6, discovered, Set.of(), false, history);
                    if (!"READ".equals(budget.getString("phase"))) throw new AssertionError("Premature finalize");
                }
            }
        }
        var result = JSONObject.of("runtime", StudyAgentSpec.snapshot(), "reads", reads, "partialProjections", projections,
                "N02OldHistoryWithNewBudget", budget);
        Files.writeString(Path.of(args[2]), JSON.toJSONString(result, JSONWriter.Feature.PrettyFormat), StandardOpenOption.CREATE_NEW);
        System.out.println("Checked " + reads.size() + " reads, " + projections.size() + " partial projections; N02 budget=" + budget.getString("phase"));
    }
}
