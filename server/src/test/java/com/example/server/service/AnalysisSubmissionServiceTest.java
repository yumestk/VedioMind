package com.example.server.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.ai.AiRateLimitService;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

class AnalysisSubmissionServiceTest {
    @Test
    void deniesAnotherUsersMediaBeforeLocksQuotaAndMq() {
        var mq = mock(RocketMQTemplate.class);
        var jobs = mock(AnalysisJobService.class);
        var mapper = mock(MediaFileMapper.class);
        var redis = mock(RedissonClient.class);
        var limits = mock(AiRateLimitService.class);
        var service = new AnalysisSubmissionService(mq, jobs, mapper, redis, limits);
        var media = new MediaFile();
        media.setId(42L);
        media.setUserId(2L);
        when(mapper.selectById(42L)).thenReturn(media);
        assertThatThrownBy(() -> service.submit(42L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mq, jobs, redis, limits);
    }
}
