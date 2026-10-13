package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_knowledge_chunks")
public class VideoKnowledgeChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mediaId;
    private String contextVersion;
    private Integer processorVersion;
    private Integer chunkIndex;
    private Long startMs;
    private Long endMs;
    private String summary;
    private String keywordsJson;
    private String embeddingJson;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
