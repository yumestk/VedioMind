package com.example.server.service;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;
import java.util.List;
import java.util.Map;

/** A single repairable tool error. Pointers use schema keys and host-generated indexes only. */
final class StudyToolFeedback extends GovernanceException {
    static final String VERSION = "json-pointer-v1";
    private final String kind;
    private final String path;
    private final String relatedPath;

    StudyToolFeedback(String kind, String path, String hint) {
        this(kind, path, hint, null);
    }

    private StudyToolFeedback(String kind, String path, String hint, String relatedPath) {
        super("INVALID_TOOL_ARGUMENTS", hint);
        this.kind = kind;
        this.path = path;
        this.relatedPath = relatedPath;
    }

    static StudyToolFeedback business(String path, String hint) {
        return new StudyToolFeedback("BUSINESS_CONTRACT", path, hint);
    }

    static JSONObject observation(GovernanceException error) {
        var result = JSONObject.of("error", error.code(), "message", error.getMessage());
        if (error instanceof StudyToolFeedback feedback) {
            result.put("kind", feedback.kind);
            result.put("path", feedback.path);
            if (feedback.relatedPath != null) result.put("relatedPath", feedback.relatedPath);
        }
        return result;
    }

    // Diagnose only a rejected value. The existing JSONSchema validator decides admission.
    static StudyToolFeedback schema(JSONObject schema, Object value, String path) {
        String type = schema.getString("type");
        if ("object".equals(type) && value instanceof Map<?, ?> fields) {
            var properties = schema.getJSONObject("properties");
            for (String name : schema.getList("required", String.class)) {
                if (!fields.containsKey(name)) {
                    if (path.isEmpty() && "coverageMaterialIds".equals(name)
                            && fields.get("note") instanceof Map<?, ?> note && note.containsKey(name))
                        return new StudyToolFeedback("SCHEMA", "/coverageMaterialIds",
                                "将该字段移到工具参数根对象，与 note 同级。",
                                "/note/coverageMaterialIds");
                    return new StudyToolFeedback("SCHEMA", path + "/" + name, "在此路径补齐必填字段。");
                }
            }
            for (String name : properties.keySet()) {
                var child = properties.getJSONObject(name);
                if (fields.containsKey(name) && !JSONSchema.of(child).isValid(fields.get(name)))
                    return schema(child, fields.get(name), path + "/" + name);
            }
            if ("/note".equals(path) && fields.containsKey("coverageMaterialIds"))
                return new StudyToolFeedback("SCHEMA", "/note/coverageMaterialIds",
                        "删除 note 内重复的覆盖字段，保留工具参数根对象的字段。",
                        "/coverageMaterialIds");
            return new StudyToolFeedback("SCHEMA", path, "仅保留工具 Schema 在此对象声明的字段。");
        }
        if ("array".equals(type) && value instanceof List<?> items) {
            int min = schema.getIntValue("minItems"), max = schema.getIntValue("maxItems");
            if (items.size() < min || items.size() > max)
                return new StudyToolFeedback("SCHEMA", path, "此数组需要 " + min + " 至 " + max + " 项。");
            var child = schema.getJSONObject("items");
            for (int i = 0; i < items.size(); i++) {
                if (!JSONSchema.of(child).isValid(items.get(i)))
                    return schema(child, items.get(i), path + "/" + i);
            }
            return new StudyToolFeedback("SCHEMA", path, "数组项须互不重复。");
        }
        String hint = switch (type) {
            case "object" -> "此处须为 JSON 对象。";
            case "array" -> "此处须为 JSON 数组。";
            case "integer" -> "此处须为整数，且在工具 Schema 指定范围内。";
            case "string" -> "此处须为字符串，并符合工具 Schema 指定的长度或枚举。";
            default -> "按工具 Schema 修正此处的类型或取值。";
        };
        return new StudyToolFeedback("SCHEMA", path, hint);
    }
}
