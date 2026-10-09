package com.example.server.controller;

import com.example.server.service.AnalysisJobService;
import com.example.server.service.AnalysisSubmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.messaging.MessagingException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalysisControllerTest {

    private final AnalysisSubmissionService submissionService = mock(AnalysisSubmissionService.class);
    private final AnalysisJobService jobService = mock(AnalysisJobService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AnalysisController(submissionService, jobService))
            .build();

    @Test
    void returnsActionableServiceUnavailableResponseWhenRocketMqCannotAcceptTheJob() throws Exception {
        when(submissionService.submit(13L))
                .thenThrow(new MessagingException("No route info for video-analysis-topic"));

        mockMvc.perform(post("/analysis/media/13"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("分析服务暂时不可用"))
                .andExpect(jsonPath("$.detail").value("RocketMQ 暂时不可用，请确认 NameServer 和 Broker 已启动后重试。"))
                .andExpect(jsonPath("$.code").value("ROCKETMQ_UNAVAILABLE"));
    }
}
