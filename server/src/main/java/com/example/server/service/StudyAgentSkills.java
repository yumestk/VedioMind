package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONReader;
import com.example.server.dto.PreparedStudyTask;
import com.example.server.service.ai.AiCallLedger;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;

import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bundled, host-owned Skills. */
public final class StudyAgentSkills {
    private static final int MAX_BYTES = 32768;
    private static final StudyAgentSkills BUNDLED = discover();

    public record Metadata(String name, String description, String version) {}

    private record Skill(Metadata metadata, Resource resource, String fingerprint) {}

    private record Document(Metadata metadata, String body, String fingerprint) {}

    private final SortedMap<String, Skill> skills = new TreeMap<>();

    StudyAgentSkills(Resource... resources) {
        for (Resource resource : resources) {
            Document document = parse(resource);
            String name = document.metadata().name();
            try {
                if (!resource.getURL().getPath().endsWith("/" + name + "/SKILL.md"))
                    throw new IllegalStateException("Skill name must match its directory: " + name);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot locate Skill: " + name, e);
            }
            if (skills.putIfAbsent(
                            name, new Skill(document.metadata(), resource, document.fingerprint()))
                    != null) throw new IllegalStateException("Duplicate Skill: " + name);
        }
        if (skills.isEmpty()) throw new IllegalStateException("No bundled Study Skills found");
    }

    private static StudyAgentSkills discover() {
        try {
            return new StudyAgentSkills(
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:skills/*/SKILL.md"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot discover bundled Study Skills", e);
        }
    }

    public static StudyAgentSkills bundled() {
        return BUNDLED;
    }

    public List<Metadata> catalog() {
        return skills.values().stream().map(Skill::metadata).toList();
    }

    public List<String> names() {
        return List.copyOf(skills.keySet());
    }

    public String fingerprint() {
        var versions = new TreeMap<String, String>();
        skills.forEach((name, skill) -> versions.put(name, skill.fingerprint()));
        return AiCallLedger.sha256(JSON.toJSONString(versions));
    }

    public JSONObject read(String name) {
        if (name == null || !skills.containsKey(name))
            throw new GovernanceException("TOOL_NOT_ALLOWED", "Skill 不在宿主允许的目录中");
        Skill skill = skills.get(name);
        // Bodies are not retained in the catalog; reopen only the selected resource for this result.
        Document document;
        try {
            document = parse(skill.resource());
        } catch (IllegalStateException changed) {
            throw new GovernanceException("TASK_RESOURCE_CHANGED", "Skill 文件不可读或已损坏，请重新加载应用并提交任务");
        }
        if (!skill.fingerprint().equals(document.fingerprint()))
            throw new GovernanceException("TASK_RESOURCE_CHANGED", "Skill 内容已变化，请重新加载应用并提交任务");
        return JSONObject.of(
                "name", name,
                "version", document.metadata().version(),
                "instructions", document.body());
    }

    static final String REQUIRED_NOTE = "course-notes";
    static final String INITIALIZATION_STAGE = "HOST_SKILL_INITIALIZED";
    static final String NOTE_ASSEMBLY = "system-suffix-v1";

    JSONObject initializeNote(PreparedStudyTask task, String stored) {
        if (!"BOUNDED_AGENT".equals(task.executionStrategy())
                || !task.deliverables().stream().map(PreparedStudyTask.OutputSlot::kind).toList().equals(List.of("NOTE")))
            throw new GovernanceException("TOOL_NOT_ALLOWED", "当前任务不允许加载课程笔记 Skill");
        String catalogSha = fingerprint();
        if (!catalogSha.equals(task.resourceSnapshot().get("skills")) || !skills.containsKey(REQUIRED_NOTE))
            throw new GovernanceException("TASK_RESOURCE_CHANGED", "冻结的课程笔记 Skill 不可用或已变化");
        var checkpoint = read(REQUIRED_NOTE);
        checkpoint.put("source", "classpath:skills/course-notes/SKILL.md");
        checkpoint.put("sourceSha256", skills.get(REQUIRED_NOTE).fingerprint());
        checkpoint.put("bodySha256", AiCallLedger.sha256(checkpoint.getString("instructions")));
        checkpoint.put("catalogSha256", catalogSha);
        checkpoint.put("assembly", NOTE_ASSEMBLY);
        if (stored != null) {
            try {
                if (checkpoint.equals(JSON.parseObject(stored, JSONReader.Feature.DuplicateKeyValueAsArray))) return checkpoint;
            } catch (JSONException invalid) {
                // A malformed checkpoint is fenced in the same way as changed contents.
            }
            throw new GovernanceException("TASK_RESOURCE_CHANGED", "宿主 Skill 检查点与冻结资源不一致");
        }
        return checkpoint;
    }

    private static Document parse(Resource resource) {
        try (var input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES)
                throw new IllegalArgumentException("Skill exceeds 32 KiB");
            String source = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            int end = source.indexOf("\n---\n", 4);
            if (!source.startsWith("---\n") || end < 0)
                throw new IllegalArgumentException("Skill requires YAML front matter");
            var yaml = new YamlMapFactoryBean();
            yaml.setResources(
                    new ByteArrayResource(
                            source.substring(4, end).getBytes(StandardCharsets.UTF_8)));
            var fields = Objects.requireNonNull(yaml.getObject());
            String name = field(fields, "name"),
                    description = field(fields, "description"),
                    version = field(fields, "version");
            if (!name.matches("[a-z][a-z0-9-]{0,63}") || description.length() > 500)
                throw new IllegalArgumentException("Invalid Skill name or description");
            String body = source.substring(end + 5).strip();
            if (body.isEmpty()) throw new IllegalArgumentException("Skill body is empty");
            return new Document(
                    new Metadata(name, description, version), body, AiCallLedger.sha256(source));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Invalid bundled Skill: " + resource.getDescription(), e);
        }
    }

    private static String field(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.contains("\n"))
            throw new IllegalArgumentException("Skill requires a single-line string: " + key);
        return text;
    }
}
