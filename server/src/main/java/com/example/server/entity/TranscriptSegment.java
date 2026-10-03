package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("transcript_segments")
public class TranscriptSegment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long mediaId;
    private Integer segmentIndex;
    private Long startMs;
    private Long endMs;
    private String text;
}
