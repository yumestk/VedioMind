package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_keyframes")
public class VideoKeyframe {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mediaId;
    private Integer frameIndex;
    private Long timestampMs;
    private String objectKey;
    private String ocrStatus;
    private Integer processorVersion;
    private Double ocrConfidence;
    private String ocrText;
    private LocalDateTime createdAt;
}
