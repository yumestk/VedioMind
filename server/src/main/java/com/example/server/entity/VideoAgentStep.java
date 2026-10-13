package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_agent_steps")
public class VideoAgentStep {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private String stage;
    private Integer roundNo;
    private String status;
    private String inputJson;
    private String outputJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
