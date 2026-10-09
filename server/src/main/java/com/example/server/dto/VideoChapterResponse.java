package com.example.server.dto;

import com.example.server.entity.VideoChapter;

public record VideoChapterResponse(
        Long id,
        Integer index,
        String title,
        Long startMs,
        Long endMs
) {
    public static VideoChapterResponse from(VideoChapter chapter) {
        return new VideoChapterResponse(
                chapter.getId(),
                chapter.getChapterIndex(),
                chapter.getTitle(),
                chapter.getStartMs(),
                chapter.getEndMs()
        );
    }
}
