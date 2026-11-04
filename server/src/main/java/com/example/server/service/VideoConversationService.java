package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.dto.ConversationMessagesResponse;
import com.example.server.dto.ConversationTurnResponse;
import com.example.server.dto.MediaTranscriptResponse;
import com.example.server.dto.VideoAgentResult;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.dto.VideoConversationResponse;
import com.example.server.dto.VideoQuestionResponse;
import com.example.server.entity.VideoAgentRun;
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
import java.util.stream.Collectors;

@Service
public class VideoConversationService {

    static final int MAX_HISTORY_MESSAGES = 12;
    static final int MAX_HISTORY_CHARACTERS = 12_000;
    static final int MAX_RETRIEVAL_QUERY_CHARACTERS = 2_000;
    static final int MAX_AGENT_CONTEXT_CHARACTERS = 6_000;
    static final int MAX_AGENT_RETRIEVAL_CONTEXT_CHARACTERS = 800;
    static final int MAX_PREVIOUS_TURN_RETRIEVAL_CHARACTERS = 600;

    private final StudyTaskPreparationService tasks;
    private final StudyEvidenceService studyEvidence;
    private final KnowledgeIndexPreparationService indexPreparation;
    private final VideoConversationStore store;
    private final MediaService mediaService;
    private final VideoQuestionService questionService;
    private final VideoKnowledgeRetriever knowledgeRetriever;
    private final RedissonClient redissonClient;
    private final VideoEvidenceContextService evidenceContextService;

    public VideoConversationService(
            VideoConversationStore store,
            MediaService mediaService,
            VideoQuestionService questionService,
            VideoKnowledgeRetriever knowledgeRetriever,
            RedissonClient redissonClient,
            VideoEvidenceContextService evidenceContextService,
            KnowledgeIndexPreparationService indexPreparation, StudyTaskPreparationService tasks, StudyEvidenceService studyEvidence
    ) {
        this.tasks = tasks; this.studyEvidence = studyEvidence;
        this.indexPreparation = indexPreparation;
        this.store = store;
        this.mediaService = mediaService;
        this.questionService = questionService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.redissonClient = redissonClient;
        this.evidenceContextService = evidenceContextService;
    }

    public List<VideoConversationResponse> list(Long mediaId, Long userId, Long courseId) {
        mediaService.requireReadable(mediaId, courseId, userId);
        return store.listByMediaId(mediaId, userId, courseId);
    }

    public VideoConversationResponse create(Long mediaId, Long userId, Long courseId) {
        mediaService.requireReadable(mediaId, courseId, userId);
        return store.create(mediaId, userId, courseId);
    }

    public VideoConversationResponse get(Long mediaId, Long conversationId, Long userId) {
        return VideoConversationResponse.from(
                requireConversationForMedia(mediaId, conversationId, userId)
        );
    }

    public ConversationMessagesResponse messages(Long conversationId, Long userId) {
        VideoConversation conversation = requireOwnedConversation(conversationId, userId);
        return store.listMessages(conversation.getId(), mediaService.canRead(conversation.getMediaId(), conversation.getCourseId(), userId));
    }

    public ConversationTurnResponse ask(Long mediaId, Long conversationId, Long userId, String question) {
        var conversation = requireConversationForMedia(mediaId, conversationId, userId);
        var task = tasks.prepare(mediaId, userId, conversationId, conversation.getCourseId(),
                new com.example.server.dto.PreparedStudyTask.Scope("VIDEO", null, List.of(mediaId)), normalizeQuestion(question));
        return askPrepared(task);
    }

    public ConversationTurnResponse askPrepared(com.example.server.dto.PreparedStudyTask task) {
        Long mediaId = task.anchorMediaId(), conversationId = task.conversationId(), userId = task.learnerId();
        String normalizedQuestion = normalizeQuestion(task.goal());
        var owned = requireConversationForMedia(mediaId, conversationId, userId);
        if (!java.util.Objects.equals(owned.getCourseId(), task.courseId())) throw new NoSuchElementException("Course context mismatch");
        tasks.assertCurrent(task);
        RLock lock = redissonClient.getLock("lock:video-conversation:" + conversationId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                throw new ConversationBusyException("This conversation is already answering a question");
            }

            VideoConversation conversation = requireConversationForMedia(mediaId, conversationId, userId);
            mediaService.requireReadable(mediaId, conversation.getCourseId(), userId);
            if (!"VIDEO".equals(task.scope().type())) {
                List<DeepSeekMessage> history = trimHistory(store.listRecentMessages(conversationId, MAX_HISTORY_MESSAGES));
                String agentContext = linkedAgentContext(conversationId);
                var evidence = studyEvidence.full(task);
                if (evidence.isEmpty()) throw new ConversationNotReadyException("所选材料尚无原文，请联系课程作者完成准备");
                boolean full = questionService.fitsPrepared(task, evidence, history, agentContext);
                if (!full) {
                    if (List.of("总结", "概括", "所有", "全部", "完整", "核心观点", "核心知识点").stream().anyMatch(normalizedQuestion::contains))
                        throw new ConversationNotReadyException("所选范围的原文超出输入预算，无法保证完整概括；请缩小范围，或按具体主题提问");
                    evidence = studyEvidence.search(task, retrievalQuery(agentContext, history, normalizedQuestion));
                }
                tasks.assertCurrent(task);
                var answer = questionService.answerPrepared(task, evidence, history, agentContext, !full);
                tasks.assertCurrent(task);
                return store.savePreparedTurn(conversation, task, answer);
            }
            MediaTranscriptResponse transcript = mediaService.getTranscript(conversation.getMediaId(), userId);
            if (transcript.segments().isEmpty()) {
                throw new ConversationNotReadyException("Analyze the video before asking questions");
            }

            List<DeepSeekMessage> history = trimHistory(
                    store.listRecentMessages(conversationId, MAX_HISTORY_MESSAGES)
            );
            String agentContext = linkedAgentContext(conversation.getId());
            List<VideoEvidenceContextService.Candidate> evidence = transcript.segments().stream()
                    .map(segment -> new VideoEvidenceContextService.Candidate(new VideoAgentCitation(
                            "TRANSCRIPT:" + segment.id(), "TRANSCRIPT", segment.id(),
                            segment.startMs(), segment.endMs(), segment.text()), true, 0)).toList();
            boolean full = questionService.fitsPrepared(task, evidence, history, agentContext);
            if (!full) {
                if (List.of("总结整段", "总结整个", "全片总结", "整段视频的核心观点", "视频提出了哪些核心观点", "整节课总结")
                        .stream().anyMatch(normalizedQuestion::contains)) {
                    throw new ConversationNotReadyException(mediaService.isStudyMode(conversation.getMediaId(), userId)
                            ? "此问题需要覆盖全片，请使用深度分析，或按具体主题提问。"
                            : "此视频字幕较长，快速问答暂不能保证全片总结完整，请按具体主题提问。");
                }
                evidence = retrieveEvidence(
                        conversation.getMediaId(), normalizedQuestion, history,
                        agentContext, userId, task
                );
            }
            tasks.assertCurrent(task);
            VideoQuestionResponse answer = questionService.answerPrepared(task, evidence, history, agentContext, !full);
            mediaService.requireReadable(mediaId, conversation.getCourseId(), userId);
            tasks.assertCurrent(task);
            return store.savePreparedTurn(conversation, task, answer);
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
        if (userId == null || !userId.equals(conversation.getLearnerId()))
            throw new NoSuchElementException("Study conversation not found");
        return conversation;
    }

    private VideoConversation requireConversationForMedia(
            Long mediaId,
            Long conversationId,
            Long userId
    ) {
        VideoConversation conversation = requireOwnedConversation(conversationId, userId);
        if (!mediaId.equals(conversation.getMediaId())) {
            throw new NoSuchElementException("Video conversation does not belong to this media");
        }
        return conversation;
    }

    public List<VideoConversationResponse> personal(Long userId) {
        return store.listPersonal(userId).stream().map(conversation -> new VideoConversationResponse(
                conversation.getId(), conversation.getMediaId(), conversation.getCourseId(),
                mediaService.canRead(conversation.getMediaId(), conversation.getCourseId(), userId),
                conversation.getTitle(), conversation.getCreatedAt(), conversation.getUpdatedAt())).toList();
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
                        "ASSISTANT".equals(message.getMessageType()) ? "assistant" : "user",
                        message.getContent()
                ))
                .toList();
    }

    private List<VideoEvidenceContextService.Candidate> retrieveEvidence(
            Long mediaId, String question, List<DeepSeekMessage> history,
            String agentContext, Long userId, com.example.server.dto.PreparedStudyTask task) {
        try {
            var result = knowledgeRetriever.search(mediaId, retrievalQuery(agentContext, history, question), () -> tasks.assertCurrent(task));
            return evidenceContextService.rankForQuestion(question, evidenceContextService.expand(result.context(), result.hits(), false));
        } catch (VideoKnowledgeIndexService.KnowledgeIndexNotReadyException exception) {
            try { mediaService.requireOwnership(mediaId, userId); }
            catch (IllegalArgumentException denied) {
                throw new ConversationNotReadyException("课程问答资料尚未就绪，请联系课程作者完成准备");
            }
            throw new KnowledgeIndexPreparationService.IndexPreparationException(
                    indexPreparation.prepare(mediaId, userId, false));
        }
    }

    private String retrievalQuery(
            String agentContext,
            List<DeepSeekMessage> history,
            String question
    ) {
        int start = Math.max(0, history.size() - 2);
        String previousTurn = history.subList(start, history.size()).stream()
                .map(message -> ("assistant".equals(message.role()) ? "上一答：" : "上一问：")
                        + message.content())
                .collect(Collectors.joining("\n"));
        previousTurn = suffix(previousTurn, MAX_PREVIOUS_TURN_RETRIEVAL_CHARACTERS);
        String resultContext = prefix(agentContext, MAX_AGENT_RETRIEVAL_CONTEXT_CHARACTERS);
        String query = (resultContext.isBlank() ? "" : "深度学习任务结果：\n" + resultContext + "\n")
                + (previousTurn.isBlank() ? "" : previousTurn + "\n")
                + "当前问题：" + question;
        return query.length() <= MAX_RETRIEVAL_QUERY_CHARACTERS
                ? query
                : query.substring(query.length() - MAX_RETRIEVAL_QUERY_CHARACTERS);
    }

    public boolean hasCompletedAgentRun(Long conversationId) {
        return store.findLatestSucceededAgentRun(conversationId).isPresent();
    }

    private String linkedAgentContext(Long conversationId) {
        VideoAgentRun run = store.findLatestSucceededAgentRun(conversationId).orElse(null);
        if (run == null || run.getResultJson() == null) return "";
        VideoAgentResult result = JSON.parseObject(run.getResultJson(), VideoAgentResult.class);
        StringBuilder context = new StringBuilder()
                .append("学习目标：").append(run.getGoal())
                .append("\n结果标题：").append(result.title())
                .append("\n核心结论：").append(result.coreConclusion());
        for (int index = 0; index < result.knowledgePoints().size(); index++) {
            VideoAgentResult.KnowledgePoint point = result.knowledgePoints().get(index);
            context.append("\n知识点 ").append(index + 1).append("：")
                    .append(point.title()).append("；").append(point.explanation());
        }
        for (int index = 0; index < result.quiz().size(); index++) {
            VideoAgentResult.QuizItem item = result.quiz().get(index);
            context.append("\n自测题 ").append(index + 1).append("：")
                    .append(item.question()).append("；答案：").append(item.answer());
        }
        return prefix(context.toString(), MAX_AGENT_CONTEXT_CHARACTERS);
    }

    private String prefix(String value, int limit) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private String suffix(String value, int limit) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= limit ? value : value.substring(value.length() - limit);
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
