package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.MediaVisualEvidence;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MediaVisualEvidenceMapper extends BaseMapper<MediaVisualEvidence> {
    @org.apache.ibatis.annotations.Select("SELECT media_id,revision,processor_version,status FROM media_visual_evidence WHERE media_id=#{id}")
    @org.apache.ibatis.annotations.Options(useCache=false, flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    MediaVisualEvidence selectVersionSource(@org.apache.ibatis.annotations.Param("id") Long id);
}
