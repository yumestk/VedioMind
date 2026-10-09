package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.entity.VideoChapter;
import com.example.server.mapper.VideoChapterMapper;
import com.example.server.service.ai.VideoChapterDraft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChapterService {

    private final VideoChapterMapper videoChapterMapper;

    public ChapterService(VideoChapterMapper videoChapterMapper) {
        this.videoChapterMapper = videoChapterMapper;
    }

    public List<VideoChapter> listByMediaId(Long mediaId) {
        return videoChapterMapper.selectList(
                new LambdaQueryWrapper<VideoChapter>()
                        .eq(VideoChapter::getMediaId, mediaId)
                        .orderByAsc(VideoChapter::getChapterIndex)
        );
    }

    @Transactional
    public List<VideoChapter> replace(Long mediaId, List<VideoChapterDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("Video chapters must not be empty");
        }

        videoChapterMapper.delete(
                new LambdaQueryWrapper<VideoChapter>()
                        .eq(VideoChapter::getMediaId, mediaId)
        );

        for (int index = 0; index < drafts.size(); index++) {
            VideoChapterDraft draft = drafts.get(index);
            VideoChapter chapter = new VideoChapter();
            chapter.setMediaId(mediaId);
            chapter.setChapterIndex(index);
            chapter.setTitle(draft.title());
            chapter.setStartMs(draft.startMs());
            chapter.setEndMs(draft.endMs());
            videoChapterMapper.insert(chapter);
        }
        return listByMediaId(mediaId);
    }
}
