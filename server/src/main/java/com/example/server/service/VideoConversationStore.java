package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.dto.ConversationMessageResponse;
import com.example.server.dto.ConversationMessagesResponse;
import com.example.server.dto.ConversationTurnResponse;
import com.example.server.dto.VideoConversationResponse;
import com.example.server.dto.VideoAgentRunResponse;
import com.example.server.dto.VideoQuestionCitationResponse;
import com.example.server.dto.VideoQuestionResponse;
import com.example.server.entity.VideoAgentRun;
import com.example.server.entity.VideoConversation;
import com.example.server.entity.VideoMessage;
import com.example.server.entity.VideoMessageCitation;
import com.example.server.mapper.VideoAgentRunMapper;
import com.example.server.mapper.VideoConversationMapper;
import com.example.server.mapper.VideoMessageCitationMapper;
import com.example.server.mapper.VideoMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class VideoConversationStore {

    private static final String DEFAULT_TITLE = "新对话";
    private static final int TITLE_LENGTH = 30;

    private final VideoConversationMapper conversationMapper;
    private final VideoMessageMapper messageMapper;
    private final VideoMessageCitationMapper citationMapper;
    private final VideoAgentRunMapper agentRunMapper;
    private final StudyAccessService access;
    private final StudyTaskPreparationService tasks;
    private final com.example.server.service.context.VideoContextService contexts;

    public VideoConversationStore(
            VideoConversationMapper conversationMapper,
            VideoMessageMapper messageMapper,
            VideoMessageCitationMapper citationMapper,
            VideoAgentRunMapper agentRunMapper,
            StudyAccessService access, StudyTaskPreparationService tasks,
            com.example.server.service.context.VideoContextService contexts
    ) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.citationMapper = citationMapper;
        this.agentRunMapper = agentRunMapper;
        this.access = access; this.tasks = tasks; this.contexts = contexts;
    }

    public VideoConversationResponse create(Long mediaId, Long learnerId, Long courseId) {
        LocalDateTime now = LocalDateTime.now();
        VideoConversation conversation = new VideoConversation();
        conversation.setMediaId(mediaId);
        conversation.setLearnerId(learnerId);
        conversation.setCourseId(courseId);
        conversation.setTitle(DEFAULT_TITLE);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return VideoConversationResponse.from(conversation);
    }

    public List<VideoConversationResponse> listByMediaId(Long mediaId, Long learnerId, Long courseId) {
        return conversationMapper.selectList(
                        new LambdaQueryWrapper<VideoConversation>()
                                .eq(VideoConversation::getMediaId, mediaId)
                                .eq(VideoConversation::getLearnerId, learnerId)
                                .eq(courseId != null, VideoConversation::getCourseId, courseId)
                                .isNull(courseId == null, VideoConversation::getCourseId)
                                .orderByDesc(VideoConversation::getUpdatedAt)
                                .orderByDesc(VideoConversation::getId)
                ).stream()
                .map(VideoConversationResponse::from)
                .toList();
    }

    public List<VideoConversation> listPersonal(Long learnerId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<VideoConversation>()
                .eq(VideoConversation::getLearnerId, learnerId).orderByDesc(VideoConversation::getUpdatedAt)
                .orderByDesc(VideoConversation::getId));
    }

    public VideoConversation getRequired(Long conversationId) {
        VideoConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new NoSuchElementException("Video conversation does not exist: " + conversationId);
        }
        return conversation;
    }

    public ConversationMessagesResponse listMessages(Long conversationId, boolean sourceAvailable) {
        List<VideoMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<VideoMessage>()
                        .eq(VideoMessage::getConversationId, conversationId)
                        .orderByAsc(VideoMessage::getId)
        );
        if (messages.isEmpty()) {
            return new ConversationMessagesResponse(conversationId, sourceAvailable, List.of());
        }

        var conversation = getRequired(conversationId);
        List<Long> messageIds = messages.stream().map(VideoMessage::getId).toList();
        Map<Long, List<VideoQuestionCitationResponse>> citationsByMessage = citationMapper.selectList(
                        new LambdaQueryWrapper<VideoMessageCitation>()
                                .in(VideoMessageCitation::getMessageId, messageIds)
                                .orderByAsc(VideoMessageCitation::getMessageId)
                                .orderByAsc(VideoMessageCitation::getCitationIndex)
                ).stream()
                .collect(Collectors.groupingBy(
                        VideoMessageCitation::getMessageId,
                        Collectors.mapping(c -> toCitation(c, conversation), Collectors.toList())
                ));
        List<String> runIds = messages.stream()
                .map(VideoMessage::getAgentRunId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, VideoAgentRunResponse> runsById = runIds.isEmpty()
                ? Map.of()
                : agentRunMapper.selectBatchIds(runIds).stream()
                .collect(Collectors.toMap(
                        VideoAgentRun::getId,
                        run -> VideoAgentRunResponse.from(run, tasks.sourceAvailable(run.getMediaId(), run.getCourseId(), run.getLearnerId(), run.getContextVersion()))
                ));

        return new ConversationMessagesResponse(
                conversationId,
                sourceAvailable,
                messages.stream()
                        .map(message -> ConversationMessageResponse.from(
                                message,
                                citationsByMessage.getOrDefault(message.getId(), List.of()),
                                message.getAgentRunId() == null ? null : runsById.get(message.getAgentRunId())
                        ))
                        .toList()
        );
    }

    public List<VideoMessage> listRecentMessages(Long conversationId, int limit) {
        List<VideoMessage> messages = new ArrayList<>(messageMapper.selectList(
                new LambdaQueryWrapper<VideoMessage>()
                        .eq(VideoMessage::getConversationId, conversationId)
                        .in(VideoMessage::getMessageType, "USER", "ASSISTANT")
                        .orderByDesc(VideoMessage::getId)
                        .last("LIMIT " + limit)
        ));
        Collections.reverse(messages);
        return messages;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ConversationTurnResponse savePreparedTurn(
            VideoConversation conversation,
            com.example.server.dto.PreparedStudyTask task,
            VideoQuestionResponse answer
    ) {
        if (!conversation.getId().equals(task.conversationId()) || !conversation.getLearnerId().equals(task.learnerId())
                || !java.util.Objects.equals(conversation.getCourseId(), task.courseId())
                || !conversation.getMediaId().equals(task.anchorMediaId())) throw new IllegalArgumentException("Task conversation mismatch");
        tasks.lockCurrent(task);
        String question = task.goal();
        LocalDateTime now = LocalDateTime.now();
        VideoMessage userMessage = newMessage(conversation.getId(), "USER", question, null, now);
        messageMapper.insert(userMessage);

        VideoMessage assistantMessage = newMessage(
                conversation.getId(), "ASSISTANT", answer.answer(), null, now
        );
        assistantMessage.setPreparedTask(com.alibaba.fastjson2.JSON.toJSONString(task));
        messageMapper.insert(assistantMessage);
        for (int index = 0; index < answer.citations().size(); index++) {
            VideoQuestionCitationResponse citation = answer.citations().get(index);
            VideoMessageCitation snapshot = new VideoMessageCitation();
            snapshot.setMessageId(assistantMessage.getId());
            snapshot.setCitationIndex(index);
            snapshot.setSegmentId(citation.segmentId());
            Long materialId = citation.materialId() == null ? conversation.getMediaId() : citation.materialId();
            var material = task.materials().stream().filter(m -> m.materialId().equals(materialId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Citation outside prepared task"));
            String type = citation.type() == null ? "TRANSCRIPT" : citation.type();
            if (!java.util.Set.of("TRANSCRIPT", "OCR").contains(type)) throw new IllegalArgumentException("Invalid citation modality");
            if (citation.contextVersion() != null && !material.version().equals(citation.contextVersion()))
                throw new IllegalArgumentException("Citation version mismatch");
            snapshot.setMaterialId(materialId); snapshot.setContextVersion(material.version()); snapshot.setType(type);
            snapshot.setEvidenceRef(materialId + ":" + type + ":" + citation.segmentId() + ":" + material.version());
            snapshot.setStartMs(citation.startMs());
            snapshot.setEndMs(citation.endMs());
            snapshot.setText(citation.text());
            citationMapper.insert(snapshot);
        }

        if (DEFAULT_TITLE.equals(conversation.getTitle())) {
            conversation.setTitle(question.substring(0, Math.min(question.length(), TITLE_LENGTH)));
        }
        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);

        return new ConversationTurnResponse(
                VideoConversationResponse.from(conversation),
                ConversationMessageResponse.from(userMessage, List.of(), null),
                ConversationMessageResponse.from(assistantMessage, answer.citations().stream().map(c -> {
                    Long id = c.materialId() == null ? task.anchorMediaId() : c.materialId();
                    var m = task.materials().stream().filter(v -> v.materialId().equals(id)).findFirst().orElseThrow();
                    return new VideoQuestionCitationResponse(c.segmentId(), c.startMs(), c.endMs(), c.text(), id,
                            m.version(), id + ":" + c.type() + ":" + c.segmentId() + ":" + m.version(), c.type(), true);
                }).toList(), null)
        );
    }

    @Transactional
    public void appendAgentRunRequest(
            Long conversationId,
            String runId,
            String goal,
            LocalDateTime createdAt
    ) {
        VideoConversation conversation = getRequired(conversationId);
        messageMapper.insert(newMessage(conversationId, "USER", goal, null, createdAt));
        messageMapper.insert(newMessage(conversationId, "AGENT_RUN", goal, runId, createdAt));
        updateConversation(conversation, goal, createdAt);
    }

    public Optional<VideoAgentRun> findLatestSucceededAgentRun(Long conversationId) {
        List<VideoMessage> runMessages = messageMapper.selectList(
                new LambdaQueryWrapper<VideoMessage>()
                        .eq(VideoMessage::getConversationId, conversationId)
                        .eq(VideoMessage::getMessageType, "AGENT_RUN")
                        .orderByDesc(VideoMessage::getId)
        );
        return runMessages.stream()
                .map(VideoMessage::getAgentRunId)
                .filter(Objects::nonNull)
                .map(agentRunMapper::selectById)
                .filter(Objects::nonNull)
                .filter(run -> "SUCCEEDED".equals(run.getStatus()) || "PARTIAL".equals(run.getStatus()))
                .findFirst();
    }

    private VideoMessage newMessage(
            Long conversationId,
            String messageType,
            String content,
            String agentRunId,
            LocalDateTime createdAt
    ) {
        VideoMessage message = new VideoMessage();
        message.setConversationId(conversationId);
        message.setMessageType(messageType);
        message.setContent(content);
        message.setAgentRunId(agentRunId);
        message.setCreatedAt(createdAt);
        return message;
    }

    private void updateConversation(
            VideoConversation conversation,
            String firstMessage,
            LocalDateTime updatedAt
    ) {
        if (DEFAULT_TITLE.equals(conversation.getTitle())) {
            conversation.setTitle(firstMessage.substring(0, Math.min(firstMessage.length(), TITLE_LENGTH)));
        }
        conversation.setUpdatedAt(updatedAt);
        conversationMapper.updateById(conversation);
    }

    private VideoQuestionCitationResponse toCitation(VideoMessageCitation citation, VideoConversation conversation) {
        boolean readable = access.canRead(citation.getMaterialId(), conversation.getCourseId(), conversation.getLearnerId());
        if (readable && citation.getContextVersion() != null)
            readable = citation.getContextVersion().equals(contexts.currentVersion(citation.getMaterialId()));
        return new VideoQuestionCitationResponse(citation.getSegmentId(), citation.getStartMs(), citation.getEndMs(),
                readable ? citation.getText() : null, citation.getMaterialId(), citation.getContextVersion(),
                citation.getEvidenceRef(), citation.getType(), readable);
    }
}
