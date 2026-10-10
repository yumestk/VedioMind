package com.example.server.service;

import com.example.server.dto.ConversationMessagesResponse;
import com.example.server.dto.ConversationTurnResponse;
import com.example.server.dto.MediaTranscriptResponse;
import com.example.server.dto.VideoConversationResponse;
import com.example.server.dto.VideoQuestionResponse;
import com.example.server.entity.VideoConversation;
import com.example.server.entity.VideoMessage;
import com.example.server.service.ai.impl.DeepSeekMessage;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

@Service
public class VideoConversationService {

    static final int MAX_HISTORY_MESSAGES = 12;
    static final int MAX_HISTORY_CHARACTERS = 12_000;

    private final VideoConversationStore store;
    private final MediaService mediaService;
    private final VideoQuestionService questionService;
    private final RedissonClient redissonClient;

    public VideoConversationService(
            VideoConversationStore store,
            MediaService mediaService,
            VideoQuestionService questionService,
            RedissonClient redissonClient
    ) {
        this.store = store;
        this.mediaService = mediaService;
        this.questionService = questionService;
        this.redissonClient = redissonClient;
    }

    public List<VideoConversationResponse> list(Long mediaId, Long userId) {
        requireMediaOwnership(mediaId, userId);
        return store.listByMediaId(mediaId);
    }

    public VideoConversationResponse create(Long mediaId, Long userId) {
        requireMediaOwnership(mediaId, userId);
        return store.create(mediaId);
    }

    public ConversationMessagesResponse messages(Long conversationId, Long userId) {
        VideoConversation conversation = requireOwnedConversation(conversationId, userId);
        return store.listMessages(conversation.getId());
    }

    public ConversationTurnResponse ask(Long conversationId, Long userId, String question) {
        String normalizedQuestion = normalizeQuestion(question);
        requireOwnedConversation(conversationId, userId);

        RLock lock = redissonClient.getLock("lock:video-conversation:" + conversationId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                throw new ConversationBusyException("This conversation is already answering a question");
            }

            VideoConversation conversation = requireOwnedConversation(conversationId, userId);
            MediaTranscriptResponse transcript = mediaService.getTranscript(conversation.getMediaId(), userId);
            if (transcript.segments().isEmpty()) {
                throw new ConversationNotReadyException("Analyze the video before asking questions");
            }

            List<DeepSeekMessage> history = trimHistory(
                    store.listRecentMessages(conversationId, MAX_HISTORY_MESSAGES)
            );
            VideoQuestionResponse answer = questionService.answer(
                    normalizedQuestion,
                    transcript.segments(),
                    history
            );
            return store.saveTurn(conversation, normalizedQuestion, answer);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring conversation lock", exception);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private VideoConversation requireOwnedConversation(Long conversationId, Long userId) {
        VideoConversation conversation = store.getRequired(conversationId);
        requireMediaOwnership(conversation.getMediaId(), userId);
        return conversation;
    }

    private void requireMediaOwnership(Long mediaId, Long userId) {
        try {
            mediaService.requireOwnership(mediaId, userId);
        } catch (IllegalArgumentException exception) {
            throw new NoSuchElementException(exception.getMessage(), exception);
        }
    }

    private String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be empty");
        }
        String normalized = question.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("Question must not exceed 500 characters");
        }
        return normalized;
    }

    private List<DeepSeekMessage> trimHistory(List<VideoMessage> messages) {
        List<VideoMessage> selected = new ArrayList<>(messages);
        int characters = selected.stream().mapToInt(message -> message.getContent().length()).sum();
        while (selected.size() > 2 && characters > MAX_HISTORY_CHARACTERS) {
            characters -= selected.remove(0).getContent().length();
            if (!selected.isEmpty()) {
                characters -= selected.remove(0).getContent().length();
            }
        }
        return selected.stream()
                .map(message -> new DeepSeekMessage(
                        "ASSISTANT".equals(message.getRole()) ? "assistant" : "user",
                        message.getContent()
                ))
                .toList();
    }

    public static class ConversationBusyException extends RuntimeException {
        public ConversationBusyException(String message) {
            super(message);
        }
    }

    public static class ConversationNotReadyException extends RuntimeException {
        public ConversationNotReadyException(String message) {
            super(message);
        }
    }
}
