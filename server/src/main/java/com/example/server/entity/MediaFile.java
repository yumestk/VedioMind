package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("media_files")
public class MediaFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String filename;
    private String status;
    private String sourceType;
    private String analysisMode;
    private String sourcePlatform;
    private String sourceUrl;
    private String externalId;
    private String objectKey;
    private String mimeType;
    private Long fileSize;
    private String contentSha256;
    private Long durationMs;
    private String transcriptSource;
    private String transcriptLanguage;
    private Integer transcriptRevision;
    private String aiSummary;
    private String aiSummaryContextVersion;
    private String coverUrl;
    private LocalDateTime uploadTime;
}
