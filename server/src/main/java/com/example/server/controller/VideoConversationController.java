package com.example.server.controller;

import com.example.server.dto.ConversationMessageRequest;
import com.example.server.dto.ConversationMessagesResponse;
import com.example.server.dto.ConversationTurnResponse;
import com.example.server.dto.VideoConversationResponse;
import com.example.server.service.VideoConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
            @RequestParam Long userId
    ) {
        try {
            return conversationService.list(mediaId, userId);
        } catch (NoSuchElementException exception) {
            throw notFound(exception);
        }
    }

    @PostMapping("/media/{mediaId}/conversations")
    public ResponseEntity<VideoConversationResponse> create(
            @PathVariable Long mediaId,
            @RequestParam Long userId
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(conversationService.create(mediaId, userId));
        } catch (NoSuchElementException exception) {
            throw notFound(exception);
        }
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ConversationMessagesResponse messages(
            @PathVariable Long conversationId,
            @RequestParam Long userId
    ) {
        try {
            return conversationService.messages(conversationId, userId);
        } catch (NoSuchElementException exception) {
            throw notFound(exception);
        }
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ConversationTurnResponse ask(
            @PathVariable Long conversationId,
            @RequestParam Long userId,
            @RequestBody ConversationMessageRequest request
    ) {
        try {
            return conversationService.ask(conversationId, userId, request.question());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (NoSuchElementException exception) {
            throw notFound(exception);
        } catch (VideoConversationService.ConversationNotReadyException
                 | VideoConversationService.ConversationBusyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
        }
    }

    private ResponseStatusException notFound(NoSuchElementException exception) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
    }
}
