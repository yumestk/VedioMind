package com.example.server.controller;

import com.example.server.security.CurrentUserId;

import com.example.server.dto.ConversationMessagesResponse;
import com.example.server.dto.VideoConversationResponse;
import com.example.server.service.VideoConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping
public class VideoConversationController {

    private final VideoConversationService conversationService;

    public VideoConversationController(VideoConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/media/{mediaId}/conversations")
    public List<VideoConversationResponse> list(
            @PathVariable Long mediaId,
            @CurrentUserId Long userId,
            @RequestParam(required = false) Long courseId
    ) {
        try {
            return conversationService.list(mediaId, userId, courseId);
        } catch (NoSuchElementException exception) {
            throw notFound(exception);
        }
    }

    @GetMapping("/conversations")
    public List<VideoConversationResponse> personal(@CurrentUserId Long userId) {
        return conversationService.personal(userId);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ConversationMessagesResponse messages(
            @PathVariable Long conversationId,
            @CurrentUserId Long userId
    ) {
        try {
            return conversationService.messages(conversationId, userId);
        } catch (NoSuchElementException exception) {
            throw notFound(exception);
        }
    }

    private ResponseStatusException notFound(NoSuchElementException exception) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
    }
}
