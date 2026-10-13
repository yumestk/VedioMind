package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_agent_runs")
public class VideoAgentRun {

    @TableId
    private String id;
    private Long mediaId;
    private String goal;
    private String contextVersion;
    private String status;
    private Integer progress;
    private Integer retryCount;
    private Integer generationCalls;
    private Integer retrievalCalls;
    private Integer inputTokens;
    private Integer outputTokens;
    private Boolean tokenUsageKnown;
    private String resultJson;
    private String citationsJson;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime finishedAt;
}
