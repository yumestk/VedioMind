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
    void initialContextContainsOnlyMetadataAndReadingLoadsOnlyTheSelectedBody() {
        var skills = StudyAgentSkills.bundled();
        assertThat(skills.names()).containsExactly("course-notes", "video-qa");
        String notes = skills.read("course-notes").getString("instructions");
        String qa = skills.read("video-qa").getString("instructions");
        assertThat(notes).contains("# 课程学习笔记", "## 执行步骤").doesNotContain(qa);
        assertThat(qa).contains("# 视频课程问答").doesNotContain(notes);
        assertThat(StudyAgentSpec.systemPrompt())
                .contains("course-notes", "video-qa", "description", "version")
                .doesNotContain(notes, qa, "## 执行步骤");
        assertThat(JSON.toJSONString(StudyAgentSpec.tools())).doesNotContain(notes, qa);
        assertThatThrownBy(() -> skills.read("../../application.properties"))
                .isInstanceOf(GovernanceException.class)
                .hasMessageContaining("允许");
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
