package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("media_visual_evidence")
public class MediaVisualEvidence {

    @TableId
    private Long mediaId;
    private String status;
    private Integer processorVersion;
    private Integer revision;
    private Integer frameCount;
    private Integer usableOcrCount;
    private Integer lowConfidenceCount;
    private Integer emptyOcrCount;
    private Integer failedOcrCount;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
