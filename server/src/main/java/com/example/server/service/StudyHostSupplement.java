package com.example.server.service;

import com.alibaba.fastjson2.JSONObject;
import com.example.server.dto.PreparedStudyTask;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.service.ai.AiCallLedger;
import java.util.*;
import java.util.regex.Pattern;

final class StudyHostSupplement {
    static final int READ_TIMEOUT_SECONDS = 2;
    static final String STAGE = "HOST_SUPPLEMENT";
    static final String VERSION = "host-window-v1";
    static final String PREFIX = "宿主补读原文数据：";
    private static final Pattern LECTURES = Pattern.compile("第\\s*(\\d+(?:\\s*[、,，和及与]\\s*\\d+)*)\\s*讲");
    private static final Pattern TITLE_NUMBER = Pattern.compile("^\\s*(\\d+)[_ .-]");
    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9-]{2,}");
    private static final Set<String> STOP = Set.of("and", "the", "for", "with", "how", "than", "from");

    record Lead(String ref, long material, String preview, List<String> queries) {}

    record Candidate(String ref, long material, String version, String kind, int matches) {}

    static Candidate choose(PreparedStudyTask task, Collection<Lead> leads, Set<String> selected,
                            Set<String> deferred, Set<String> singleAttempts, Map<String, VideoAgentCitation> read) {
        Set<String> requested = new HashSet<>();
        var mentions = LECTURES.matcher(task.originalRequest());
        while (mentions.find()) requested.addAll(Arrays.asList(mentions.group(1).split("\\D+")));
        Map<String, List<PreparedStudyTask.Material>> numbered = new HashMap<>();
        for (var material : task.materials()) {
            var number = TITLE_NUMBER.matcher(material.title());
            if (number.find() && requested.contains(number.group(1)))
                numbered.computeIfAbsent(number.group(1), unused -> new ArrayList<>()).add(material);
        }
        Set<Long> alreadyRead = new HashSet<>();
        read.values().forEach(e -> alreadyRead.add(e.materialId()));
        Map<Long, String> missing = new HashMap<>();
        numbered.values().stream().filter(values -> values.size() == 1).map(List::getFirst)
                .filter(m -> task.scope().materialIds().contains(m.materialId()) && !alreadyRead.contains(m.materialId()))
                .forEach(m -> missing.put(m.materialId(), m.version()));

        Candidate best = null;
        for (var lead : leads) {
            String version = missing.get(lead.material());
            if (version == null || !lead.ref().startsWith(lead.material() + ":")
                    || !lead.ref().endsWith(":" + version) || read.containsKey(lead.ref())
                    || singleAttempts.contains(lead.ref())
                    || (selected.contains(lead.ref()) && !deferred.contains(lead.ref()))) continue;
            var preview = words(lead.preview());
            int matches = lead.queries().stream().mapToInt(query -> {
                var terms = words(query + " " + task.originalRequest());
                terms.retainAll(preview);
                return terms.size();
            }).max().orElse(0);
            if (matches < 2) continue;
            String kind = deferred.contains(lead.ref()) ? "DEFERRED_CAPACITY" : "UNSELECTED";
            if (best == null || matches > best.matches()
                    || (matches == best.matches() && "DEFERRED_CAPACITY".equals(kind)
                            && !kind.equals(best.kind())))
                best = new Candidate(lead.ref(), lead.material(), version, kind, matches);
        }
        return best;
    }

    private static Set<String> words(String text) {
        var result = new HashSet<String>();
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.length() > 4 && word.endsWith("s")) word = word.substring(0, word.length() - 1);
            if (!STOP.contains(word)) result.add(word);
        }
        return result;
    }

    static JSONObject message(String anchor, List<VideoAgentCitation> evidence) {
        return JSONObject.of("role", "user", "content", PREFIX + AiCallLedger.canonicalJson(
                JSONObject.of("kind", "host_supplement_evidence", "anchor", anchor, "evidence", evidence)));
    }
}
