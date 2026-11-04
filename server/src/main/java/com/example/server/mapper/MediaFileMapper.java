package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.MediaFile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {

    @org.apache.ibatis.annotations.Select("SELECT id,source_type,transcript_revision FROM media_files WHERE id=#{id}")
    @org.apache.ibatis.annotations.Options(useCache=false, flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    MediaFile selectVersionSource(@org.apache.ibatis.annotations.Param("id") Long id);
}