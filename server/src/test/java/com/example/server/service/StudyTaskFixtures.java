package com.example.server.service;

import com.example.server.dto.PreparedStudyTask;
import java.util.List;

public final class StudyTaskFixtures {
    private StudyTaskFixtures() {}
    public static PreparedStudyTask video(Long media, Long learner, Long conversation, Long course, String goal, String version) {
        return PreparedStudyTask.create("test-task", learner, conversation, course, media, course == null ? null : 1L,
                new PreparedStudyTask.Scope("VIDEO", null, List.of(media)),
                List.of(new PreparedStudyTask.Material(media, null, null, "lecture", version, "AVAILABLE")), goal, goal,
                List.of("NOTE"), "COURSE_MATERIAL_ONLY", PreparedStudyTask.Budget.standard(55296,8192,3,6,12));
    }
}
