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

    public VideoConversationStore(
            VideoConversationMapper conversationMapper,
            VideoMessageMapper messageMapper,
            VideoMessageCitationMapper citationMapper,
            VideoAgentRunMapper agentRunMapper
    ) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.citationMapper = citationMapper;
        this.agentRunMapper = agentRunMapper;
    }

    public VideoConversationResponse create(Long mediaId) {
        LocalDateTime now = LocalDateTime.now();
        VideoConversation conversation = new VideoConversation();
        conversation.setMediaId(mediaId);
        conversation.setTitle(DEFAULT_TITLE);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return VideoConversationResponse.from(conversation);
    }

    public List<VideoConversationResponse> listByMediaId(Long mediaId) {
        return conversationMapper.selectList(
                        new LambdaQueryWrapper<VideoConversation>()
                                .eq(VideoConversation::getMediaId, mediaId)
                                .orderByDesc(VideoConversation::getUpdatedAt)
                                .orderByDesc(VideoConversation::getId)
                ).stream()
                .map(VideoConversationResponse::from)
                .toList();
    }

    public VideoConversation getRequired(Long conversationId) {
        VideoConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new NoSuchElementException("Video conversation does not exist: " + conversationId);
        }
        return conversation;
    }

    public ConversationMessagesResponse listMessages(Long conversationId) {
        List<VideoMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<VideoMessage>()
                        .eq(VideoMessage::getConversationId, conversationId)
                        .orderByAsc(VideoMessage::getId)
        );
        if (messages.isEmpty()) {
            return new ConversationMessagesResponse(conversationId, List.of());
        }

        List<Long> messageIds = messages.stream().map(VideoMessage::getId).toList();
        Map<Long, List<VideoQuestionCitationResponse>> citationsByMessage = citationMapper.selectList(
                        new LambdaQueryWrapper<VideoMessageCitation>()
                                .in(VideoMessageCitation::getMessageId, messageIds)
                                .orderByAsc(VideoMessageCitation::getMessageId)
                                .orderByAsc(VideoMessageCitation::getCitationIndex)
                ).stream()
                .collect(Collectors.groupingBy(
                        VideoMessageCitation::getMessageId,
                        Collectors.mapping(VideoConversationStore::toCitation, Collectors.toList())
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
                        run -> VideoAgentRunResponse.from(run)
                ));

        return new ConversationMessagesResponse(
                conversationId,
                messages.stream()
                        .map(message -> ConversationMessageResponse.from(
                                message,
                                citationsByMessage.getOrDefault(message.getId(), List.of()),
                                runsById.get(message.getAgentRunId())
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

    @Transactional
    public ConversationTurnResponse saveTurn(
            VideoConversation conversation,
            String question,
            VideoQuestionResponse answer
    ) {
        LocalDateTime now = LocalDateTime.now();
        VideoMessage userMessage = newMessage(conversation.getId(), "USER", question, null, now);
        messageMapper.insert(userMessage);

        VideoMessage assistantMessage = newMessage(
                conversation.getId(), "ASSISTANT", answer.answer(), null, now
        );
        messageMapper.insert(assistantMessage);
        for (int index = 0; index < answer.citations().size(); index++) {
            VideoQuestionCitationResponse citation = answer.citations().get(index);
            VideoMessageCitation snapshot = new VideoMessageCitation();
            snapshot.setMessageId(assistantMessage.getId());
            snapshot.setCitationIndex(index);
            snapshot.setSegmentId(citation.segmentId());
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
                ConversationMessageResponse.from(assistantMessage, answer.citations(), null)
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
                .filter(run -> "SUCCEEDED".equals(run.getStatus()))
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

    private static VideoQuestionCitationResponse toCitation(VideoMessageCitation citation) {
        return new VideoQuestionCitationResponse(
                citation.getSegmentId(),
                citation.getStartMs(),
                citation.getEndMs(),
                citation.getText()
        );
    }
}
