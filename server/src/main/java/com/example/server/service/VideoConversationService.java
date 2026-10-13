package com.example.server.service;

import com.example.server.dto.ConversationMessagesResponse;
import com.example.server.dto.ConversationTurnResponse;
import com.example.server.dto.MediaTranscriptResponse;
import com.example.server.dto.TranscriptSegmentResponse;
import com.example.server.dto.VideoConversationResponse;
import com.example.server.dto.VideoQuestionResponse;
import com.example.server.entity.VideoConversation;
import com.example.server.entity.VideoMessage;
import com.example.server.service.ai.impl.DeepSeekMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class VideoConversationService {

    private static final Logger log = LoggerFactory.getLogger(VideoConversationService.class);
    static final int MAX_HISTORY_MESSAGES = 12;
    static final int MAX_HISTORY_CHARACTERS = 12_000;
    static final int MAX_RETRIEVAL_QUERY_CHARACTERS = 2_000;

    private final VideoConversationStore store;
    private final MediaService mediaService;
    private final VideoQuestionService questionService;
    private final VideoKnowledgeRetriever knowledgeRetriever;
    private final RedissonClient redissonClient;

    public VideoConversationService(
            VideoConversationStore store,
            MediaService mediaService,
            VideoQuestionService questionService,
            VideoKnowledgeRetriever knowledgeRetriever,
            RedissonClient redissonClient
    ) {
        this.store = store;
        this.mediaService = mediaService;
        this.questionService = questionService;
        this.knowledgeRetriever = knowledgeRetriever;
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
            List<TranscriptSegmentResponse> evidence = transcript.segments();
            if (mediaService.isStudyMode(conversation.getMediaId(), userId)) {
                evidence = retrieveEvidence(
                        conversation.getMediaId(), normalizedQuestion, history, transcript.segments()
                );
            }
            VideoQuestionResponse answer = questionService.answer(
                    normalizedQuestion,
                    evidence,
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

    private List<TranscriptSegmentResponse> retrieveEvidence(
            Long mediaId,
            String question,
            List<DeepSeekMessage> history,
            List<TranscriptSegmentResponse> transcript
    ) {
        List<VideoKnowledgeRetriever.KnowledgeSearchHit> hits;
        try {
            hits = knowledgeRetriever.search(mediaId, retrievalQuery(history, question));
        } catch (VideoKnowledgeIndexService.KnowledgeIndexNotReadyException exception) {
            throw new ConversationNotReadyException(
                    "长视频知识索引尚未就绪，请重新进行 AI 分析"
            );
        }

        Map<Long, TranscriptSegmentResponse> byId = transcript.stream()
                .collect(Collectors.toMap(
                        TranscriptSegmentResponse::id,
                        Function.identity()
                ));
        LinkedHashSet<Long> selectedIds = hits.stream()
                .flatMap(hit -> hit.transcriptSegmentIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<TranscriptSegmentResponse> evidence = selectedIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        log.info("RAG evidence selected for media {}: chunks={}, segments={}, characters={}",
                mediaId,
                hits.stream().map(VideoKnowledgeRetriever.KnowledgeSearchHit::chunkIndex).toList(),
                evidence.size(),
                evidence.stream().mapToInt(segment -> segment.text().length()).sum());
        return evidence;
    }

    private String retrievalQuery(List<DeepSeekMessage> history, String question) {
        int start = Math.max(0, history.size() - 2);
        String previousTurn = history.subList(start, history.size()).stream()
                .map(message -> ("assistant".equals(message.role()) ? "上一答：" : "上一问：")
                        + message.content())
                .collect(Collectors.joining("\n"));
        String query = (previousTurn.isBlank() ? "" : previousTurn + "\n")
                + "当前问题：" + question;
        return query.length() <= MAX_RETRIEVAL_QUERY_CHARACTERS
                ? query
                : query.substring(query.length() - MAX_RETRIEVAL_QUERY_CHARACTERS);
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
