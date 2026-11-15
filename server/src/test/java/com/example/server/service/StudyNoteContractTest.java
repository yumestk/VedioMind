package com.example.server.service;

import static org.assertj.core.api.Assertions.*;
import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.schema.JSONSchema;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudyNoteContractTest {
    @Test void deletingARejectedPointAlsoRemovesItsOverviewClaimAndCitations() {
        var bst = JSONObject.of("title", "BST 约束左右子树的键值", "explanation", "原文定义及条件", "evidenceRefs", List.of("bst"));
        var heap = JSONObject.of("title", "堆实现优先队列", "explanation", "尚未读取原文", "evidenceRefs", List.of("unread"));
        var input = JSONObject.of("title", "对比笔记", "knowledgePoints", List.of(bst, heap), "quiz", List.of(),
                "goalCoverage", List.of(JSONObject.of("requirement", "对比两种树", "pointIndexes", List.of(0, 1), "gap", "")));
        assertThat(JSONSchema.of(StudyAgentSpec.noteInputSchema()).isValid(input)).isTrue();
        var before = StudyArtifactService.noteFromPoints(input);
        assertThat(before.getString("coreConclusion")).contains("堆实现优先队列");
        input.put("knowledgePoints", List.of(bst));
        input.put("goalCoverage", List.of(JSONObject.of("requirement", "BST性质", "pointIndexes", List.of(0), "gap", ""),
                JSONObject.of("requirement", "堆性质", "pointIndexes", List.of(), "gap", "堆的证据尚未读取")));
        var repaired = StudyArtifactService.noteFromPoints(input);
        assertThat(repaired.getString("coreConclusion")).isEqualTo(bst.getString("title"));
        assertThat(repaired.getList("coreEvidenceRefs", String.class)).containsExactly("bst");
        assertThat(JSONSchema.of(StudyAgentSpec.noteSchema()).isValid(repaired)).isTrue();
        assertThat(JSONSchema.of(StudyAgentSpec.noteInputSchema()).isValid(before)).isFalse();
        assertThat(input).doesNotContainKey("coreConclusion");
        assertThat(repaired.getList("gaps", String.class)).containsExactly("堆性质：堆的证据尚未读取");
    }

    @Test void coverageRequiresExistingPointsOrAnExplicitGap() {
        var input = JSONObject.of("title", "笔记", "knowledgePoints", List.of(JSONObject.of(
                "title", "有据结论", "explanation", "原文", "evidenceRefs", List.of("ref"))), "quiz", List.of());
        for (var goal : List.of(JSONObject.of("requirement", "目标", "pointIndexes", List.of(1), "gap", ""),
                JSONObject.of("requirement", "目标", "pointIndexes", List.of(), "gap", ""))) {
            input.put("goalCoverage", List.of(goal));
            assertThatThrownBy(() -> StudyArtifactService.noteFromPoints(input)).hasMessageContaining("goalCoverage[0]");
        }
    }
    @Test void partiallySupportedGoalKeepsSupportedPointsAndDeclaresItsGap() {
        var input = JSONObject.of("title", "部分对比", "knowledgePoints", List.of(JSONObject.of(
                "title", "已读结论", "explanation", "有条件的原文", "evidenceRefs", List.of("read"))),
                "quiz", List.of(), "goalCoverage", List.of(JSONObject.of("requirement", "比较三种结构",
                        "pointIndexes", List.of(0), "gap", "其余两种结构尚未读取")));
        var note = StudyArtifactService.noteFromPoints(input);
        assertThat(note.getString("coreConclusion")).isEqualTo("已读结论");
        assertThat(note.getList("coreEvidenceRefs", String.class)).containsExactly("read");
        assertThat(note.getList("gaps", String.class)).containsExactly("比较三种结构：其余两种结构尚未读取");
        assertThat(JSONSchema.of(StudyAgentSpec.noteSchema()).isValid(note)).isTrue();
    }

}
