package com.example.server.service.external;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.TranscriptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ExternalVideoStore {

    private final MediaFileMapper mediaFileMapper;
    private final TranscriptService transcriptService;

    public ExternalVideoStore(MediaFileMapper mediaFileMapper, TranscriptService transcriptService) {
        this.mediaFileMapper = mediaFileMapper;
        this.transcriptService = transcriptService;
    }

    @Transactional
    public MediaFile save(Long userId, ResolvedExternalVideo resolved) {
        MediaFile media = new MediaFile();
        media.setUserId(userId);
        media.setFilename(truncate(resolved.title(), 500));
        media.setStatus("COMPLETED");
        media.setSourceType("EXTERNAL_URL");
        media.setSourcePlatform(resolved.platform());
        media.setSourceUrl(truncate(resolved.sourceUrl(), 1000));
        media.setExternalId(truncate(resolved.externalId(), 100));
        media.setDurationMs(resolved.durationMs());
        media.setTranscriptSource(resolved.transcriptSource());
        media.setTranscriptLanguage(resolved.transcriptLanguage());
        media.setCoverUrl(truncate(resolved.coverUrl(), 1000));
        media.setUploadTime(LocalDateTime.now());
        mediaFileMapper.insert(media);
        transcriptService.replace(media.getId(), resolved.segments());
        return media;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
