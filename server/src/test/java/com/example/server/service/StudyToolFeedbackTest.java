package com.example.server.service;

import static org.assertj.core.api.Assertions.*;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StudyToolFeedbackTest {
    private final StudyAgentToolDispatcher dispatcher = new StudyAgentToolDispatcher(null, null, null, null);

    private static JSONObject args() {
        var point = JSONObject.of("title", "知识点", "explanation", "原文", "evidenceRefs", List.of("ref"));
        var goal = JSONObject.of("requirement", "目标", "pointIndexes", List.of(0), "gap", "");
        var note = JSONObject.of("title", "笔记", "knowledgePoints", List.of(point),
                "quiz", List.of(), "goalCoverage", List.of(goal));
        return JSONObject.of("note", note, "slotId", "slot", "coverageMaterialIds", List.of(10L));
    }

    private JSONObject rejected(String raw, String kind, String path) {
        var wire = JSONObject.of("id", "test", "function", JSONObject.of("name", "save_note", "arguments", raw));
        var error = catchThrowableOfType(StudyToolFeedback.class, () -> dispatcher.validate(wire, null));
        assertThat(error).isNotNull();
        var feedback = StudyToolFeedback.observation(error);
        assertThat(feedback).containsEntry("error", "INVALID_TOOL_ARGUMENTS")
                .containsEntry("kind", kind).containsEntry("path", path);
        assertThat(feedback.getString("message")).isNotBlank();
        assertThat(feedback.toJSONString().length()).isLessThan(240);
        return feedback;
    }

    @Test void distinguishesSyntaxFromAValidNonObject() {
        rejected("{\"slotId\":", "JSON_SYNTAX", "");
        rejected("[]", "SCHEMA", "");
    }

    @Test void locatesMissingAndMisnestedCoverage() {
        var input = args();
        var coverage = input.remove("coverageMaterialIds");
        assertThat(rejected(input.toJSONString(), "SCHEMA", "/coverageMaterialIds"))
                .doesNotContainKey("relatedPath");
        input.getJSONObject("note").put("coverageMaterialIds", coverage);
        assertThat(rejected(input.toJSONString(), "SCHEMA", "/coverageMaterialIds"))
                .containsEntry("relatedPath", "/note/coverageMaterialIds");
        input.put("coverageMaterialIds", coverage);
        assertThat(rejected(input.toJSONString(), "SCHEMA", "/note/coverageMaterialIds"))
                .containsEntry("relatedPath", "/coverageMaterialIds");
    }

    @Test void locatesMissingAndWronglyTypedNestedFields() {
        var input = args();
        var point = (JSONObject) input.getJSONObject("note").getList("knowledgePoints", JSONObject.class).getFirst();
        point.remove("explanation");
        rejected(input.toJSONString(), "SCHEMA", "/note/knowledgePoints/0/explanation");
        point.put("explanation", 12);
        rejected(input.toJSONString(), "SCHEMA", "/note/knowledgePoints/0/explanation");
        point.put("explanation", "原文");
        point.put("evidenceRefs", List.of(false));
        rejected(input.toJSONString(), "SCHEMA", "/note/knowledgePoints/0/evidenceRefs/0");
    }

    @Test void unreadFeedbackLocatesTheClaimAndKeepsForbiddenValuesOut() {
        String privateRef = "other-users-resource";
        var note = args().getJSONObject("note");
        var point = note.getList("knowledgePoints", JSONObject.class).getFirst();
        point.put("evidenceRefs", List.of(privateRef));
        assertThat(JSONSchema.of(StudyAgentSpec.noteInputSchema()).isValid(note)).isTrue();
        var error = catchThrowableOfType(StudyToolFeedback.class,
                () -> StudyArtifactService.validate(StudyArtifactService.noteFromPoints(note), Map.of()));
        var feedback = StudyToolFeedback.observation(error);
        assertThat(feedback).containsEntry("error", "INVALID_TOOL_ARGUMENTS")
                .containsEntry("kind", "UNREAD_REFERENCE")
                .containsEntry("path", "/note/knowledgePoints/0/evidenceRefs/0");
        assertThat(feedback.toJSONString()).contains("复核对应主张与引用", "请勿只删改引用 ID")
                .doesNotContain(privateRef).hasSizeLessThan(240);
    }

    @Test void hostilePropertyNamesAndOversizedInputAreNotEchoed() {
        String privateKey = "other-users-resource".repeat(1000);
        var input = args();
        input.getJSONObject("note").put(privateKey, privateKey);
        assertThat(rejected(input.toJSONString(), "ARGUMENT_SIZE", "").toJSONString())
                .doesNotContain("other-users-resource");
        input.getJSONObject("note").put(privateKey, "value");
        assertThat(rejected(input.toJSONString(), "SCHEMA", "/note").toJSONString())
                .doesNotContain("other-users-resource");
    }

    @Test void hardFailuresKeepTheirOriginalEnvelope() {
        assertThat(StudyToolFeedback.observation(new GovernanceException("TOOL_NOT_ALLOWED", "输出槽不属于当前任务")))
                .isEqualTo(JSONObject.of("error", "TOOL_NOT_ALLOWED", "message", "输出槽不属于当前任务"));
    }
}
