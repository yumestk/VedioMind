package com.example.server.service;

import static org.assertj.core.api.Assertions.*;

import com.alibaba.fastjson2.JSON;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.jar.*;

class StudyAgentSkillsTest {
    @TempDir Path directory;

    private String document(String name, String body) {
        return "---\nname: "
                + name
                + "\ndescription: A learning skill\nversion: \"1\"\n---\n"
                + body;
    }

    private Resource file(String folder, String source) throws Exception {
        Path path = directory.resolve(folder).resolve("SKILL.md");
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
        return new FileSystemResource(path);
    }

    @Test
    void initialContextContainsTheCompleteRequiredNotesSkill() {
        var skills = StudyAgentSkills.bundled();
        assertThat(skills.names()).containsExactly("course-notes", "video-qa");
        String notes = skills.read("course-notes").getString("instructions");
        String qa = skills.read("video-qa").getString("instructions");
        assertThat(notes).contains("# 课程学习笔记", "## 执行步骤").doesNotContain(qa);
        assertThat(qa).contains("# 视频课程问答").doesNotContain(notes);
        assertThat(StudyAgentSpec.systemPrompt())
                .contains("course-notes", "## 执行步骤")
                .endsWith(notes).doesNotContain(qa, "video-qa", "read_skill");
        assertThat(JSON.toJSONString(StudyAgentSpec.tools())).doesNotContain(notes, qa, "read_skill");
        assertThat(StudyAgentSpec.tools()).extracting(t -> t.getJSONObject("function").getString("name"))
                .containsExactly("get_course_structure", "search_course_evidence", "read_evidence", "get_learning_state", "save_note");
        assertThatThrownBy(() -> skills.read("../../application.properties"))
                .isInstanceOf(GovernanceException.class)
                .hasMessageContaining("允许");
    }

    @Test
    void initializationRequiresTheBundledNoteSkillAndExactCheckpoint() throws Exception {
        var resource = file("course-notes", document("course-notes", "Original body"));
        var registry = new StudyAgentSkills(resource);
        var task = noteTask(registry);
        var checkpoint = registry.initializeNote(task, null);
        assertThat(registry.initializeNote(task, checkpoint.toJSONString())).isEqualTo(checkpoint);
        for (String field : java.util.List.of("instructions", "bodySha256", "source", "sourceSha256",
                "version", "assembly", "catalogSha256")) {
            var changed = JSON.parseObject(checkpoint.toJSONString());
            changed.put(field, "forged");
            assertThatExceptionOfType(GovernanceException.class)
                    .isThrownBy(() -> registry.initializeNote(task, changed.toJSONString()))
                    .satisfies(e -> assertThat(e.code()).isEqualTo("TASK_RESOURCE_CHANGED"));
        }
        var missing = new StudyAgentSkills(file("video-qa", document("video-qa", "Other skill")));
        assertThatExceptionOfType(GovernanceException.class)
                .isThrownBy(() -> missing.initializeNote(noteTask(missing), null))
                .satisfies(e -> assertThat(e.code()).isEqualTo("TASK_RESOURCE_CHANGED"));
        Files.writeString(resource.getFile().toPath(), document("course-notes", "Changed body"));
        for (var catalog : java.util.List.of(registry, new StudyAgentSkills(resource))) {
            assertThatExceptionOfType(GovernanceException.class)
                    .isThrownBy(() -> catalog.initializeNote(task, checkpoint.toJSONString()))
                    .satisfies(e -> assertThat(e.code()).isEqualTo("TASK_RESOURCE_CHANGED"));
        }
    }

    private static com.example.server.dto.PreparedStudyTask noteTask(StudyAgentSkills catalog) {
        return JSON.parseObject(com.alibaba.fastjson2.JSONObject.of("executionStrategy", "BOUNDED_AGENT",
                "deliverables", java.util.List.of(com.alibaba.fastjson2.JSONObject.of("kind", "NOTE", "slotId", "slot",
                        "constraints", java.util.Map.of())),
                "resourceSnapshot", java.util.Map.of("skills", catalog.fingerprint()),
                "materials", java.util.List.of(), "taskTypes", java.util.List.of("NOTE")).toJSONString(),
                com.example.server.dto.PreparedStudyTask.class);
    }

    @Test
    void bodyChangesAlterTheSnapshotEvenWithoutAManualVersionBumpAndBlockStaleReads()
            throws Exception {
        Resource resource = file("notes", document("notes", "Original body"));
        var before = new StudyAgentSkills(resource);
        assertThat(before.read("notes").getString("instructions")).isEqualTo("Original body");
        file("notes", document("notes", "Changed body"));
        var after = new StudyAgentSkills(resource);
        assertThat(after.fingerprint()).isNotEqualTo(before.fingerprint());
        assertThat(after.read("notes").getString("version")).isEqualTo("1");
        assertThatThrownBy(() -> before.read("notes"))
                .isInstanceOf(GovernanceException.class)
                .hasMessageContaining("内容已变化");
        Files.delete(resource.getFile().toPath());
        assertThatThrownBy(() -> after.read("notes"))
                .isInstanceOf(GovernanceException.class)
                .hasMessageContaining("不可读");
    }

    @Test
    void invalidMetadataFolderMismatchAndDuplicateNamesFailAtCatalogConstruction()
            throws Exception {
        assertThatThrownBy(() -> new StudyAgentSkills(file("notes", "# Missing metadata")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StudyAgentSkills(file("wrong", document("notes", "Body"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("directory");
        Resource resource = file("notes", document("notes", "Body"));
        assertThatThrownBy(() -> new StudyAgentSkills(resource, resource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> new StudyAgentSkills(file("notes", document("notes", " "))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void catalogDiscoveryAndOnDemandReadWorkFromAJarWithoutAFilesystemDirectory() throws Exception {
        Path jar = directory.resolve("skills.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entry :
                    new String[] {"skills/", "skills/jar-notes/", "skills/jar-notes/SKILL.md"}) {
                output.putNextEntry(new JarEntry(entry));
                if (entry.endsWith("SKILL.md"))
                    output.write(
                            document("jar-notes", "Body in jar").getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        try (var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            var resources =
                    new PathMatchingResourcePatternResolver(loader)
                            .getResources("classpath*:skills/*/SKILL.md");
            var skills = new StudyAgentSkills(resources);
            assertThat(skills.names()).containsExactly("jar-notes");
            assertThat(skills.read("jar-notes").getString("instructions")).isEqualTo("Body in jar");
        }
    }
}
