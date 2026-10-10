package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("video_message_citations")
public class VideoMessageCitation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long messageId;
    private Integer citationIndex;
    private Long segmentId;
    private Long startMs;
    private Long endMs;
    private String text;
}
