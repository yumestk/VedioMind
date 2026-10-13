package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("media_upload_sessions")
public class MediaUploadSession {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long userId;
    private String uploadId;
    private String objectKey;
    private String filename;
    private String mimeType;
    private Long fileSize;
    private Long partSize;
    private String contentSha256;
    private String status;
    private Long mediaId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
