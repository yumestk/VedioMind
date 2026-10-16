package com.example.server.controller;

import com.example.server.dto.AssistantDispatchRequest;
import com.example.server.dto.AssistantDispatchResponse;
import com.example.server.service.AssistantDispatchService;
import com.example.server.service.VideoConversationService;
import com.example.server.service.VideoKnowledgeIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.messaging.MessagingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

@RestController
@RequestMapping
@Slf4j
public class AssistantController {

    private final AssistantDispatchService dispatchService;

    public AssistantController(AssistantDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @PostMapping("/media/{mediaId}/assistant/dispatch")
    public AssistantDispatchResponse dispatch(
            @PathVariable Long mediaId,
            @RequestParam Long userId,
            @RequestBody AssistantDispatchRequest request
    ) {
        try {
            return dispatchService.dispatch(mediaId, userId, request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        } catch (VideoKnowledgeIndexService.KnowledgeIndexNotReadyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "长视频知识索引尚未就绪，请先完成 AI 分析", exception);
        } catch (VideoConversationService.ConversationNotReadyException
                 | VideoConversationService.ConversationBusyException
                 | AssistantDispatchService.AgentRunBusyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
        }
    }

    @ExceptionHandler(MessagingException.class)
    public ProblemDetail handleRocketMqUnavailable(MessagingException exception) {
        log.warn("RocketMQ rejected the Agent task submission: {}", exception.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RocketMQ 暂时不可用，请确认 NameServer 和 Broker 已启动后重试。"
        );
        problem.setTitle("深度分析暂时不可用");
        problem.setProperty("code", "ROCKETMQ_UNAVAILABLE");
        return problem;
    }
}
