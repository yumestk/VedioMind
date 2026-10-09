package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("video_chapters")
public class VideoChapter {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long mediaId;
    private Integer chapterIndex;
    private String title;
    private Long startMs;
    private Long endMs;
}
