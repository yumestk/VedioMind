package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_conversations")
public class VideoConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long mediaId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
