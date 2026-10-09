package com.example.server.service.ai;

import com.example.server.entity.TranscriptSegment;

import java.util.List;

public interface ChapterGenerator {

    List<VideoChapterDraft> generate(List<TranscriptSegment> segments);
}
