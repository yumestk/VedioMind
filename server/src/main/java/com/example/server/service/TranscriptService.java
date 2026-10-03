package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.entity.TranscriptSegment;
import com.example.server.mapper.TranscriptSegmentMapper;
import com.example.server.service.ai.TranscriptSegmentDraft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TranscriptService {

    private final TranscriptSegmentMapper transcriptSegmentMapper;

    public TranscriptService(TranscriptSegmentMapper transcriptSegmentMapper) {
        this.transcriptSegmentMapper = transcriptSegmentMapper;
    }

    public List<TranscriptSegment> listByMediaId(Long mediaId) {
        return transcriptSegmentMapper.selectList(
                new LambdaQueryWrapper<TranscriptSegment>()
                        .eq(TranscriptSegment::getMediaId, mediaId)
                        .orderByAsc(TranscriptSegment::getSegmentIndex)
        );
    }

    @Transactional
    public List<TranscriptSegment> replace(Long mediaId, List<TranscriptSegmentDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("Transcript segments must not be empty");
        }

        transcriptSegmentMapper.delete(
                new LambdaQueryWrapper<TranscriptSegment>()
                        .eq(TranscriptSegment::getMediaId, mediaId)
        );

        for (int index = 0; index < drafts.size(); index++) {
            TranscriptSegmentDraft draft = drafts.get(index);
            TranscriptSegment segment = new TranscriptSegment();
            segment.setMediaId(mediaId);
            segment.setSegmentIndex(index);
            segment.setStartMs(draft.startMs());
            segment.setEndMs(draft.endMs());
            segment.setText(draft.text());
            transcriptSegmentMapper.insert(segment);
        }
        return listByMediaId(mediaId);
    }

    public String joinText(List<TranscriptSegment> segments) {
        return segments.stream()
                .map(TranscriptSegment::getText)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
