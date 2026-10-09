package com.example.server.dto;

import java.util.List;

public record MediaChaptersResponse(
        Long mediaId,
        List<VideoChapterResponse> chapters
) {
}
