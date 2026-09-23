package com.myanimal.org.IA_service.infrastructure.adapters.out.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.domain.model.Conversation;
import com.myanimal.org.IA_service.domain.model.Message;
import com.myanimal.org.IA_service.domain.model.MessageAttachment;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;

// "test" usa NoopConversationRepositoryPort (src/test): el perfil test no monta datasource/JPA.
@Profile("!test")
@Component
public class JpaConversationRepositoryAdapter implements ConversationRepositoryPort {

    private final ConversationJpaRepository conversationJpaRepository;
    private final MessageJpaRepository messageJpaRepository;

    public JpaConversationRepositoryAdapter(ConversationJpaRepository conversationJpaRepository,
            MessageJpaRepository messageJpaRepository) {
        this.conversationJpaRepository = conversationJpaRepository;
        this.messageJpaRepository = messageJpaRepository;
    }

    @Override
    public Conversation create(UUID userId, String titulo) {
        ConversationEntity entity = ConversationEntity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .titulo(titulo)
                .build();
        return toConversation(conversationJpaRepository.save(entity));
    }

    @Override
    public Optional<Conversation> findByIdAndUser(UUID conversationId, UUID userId) {
        return conversationJpaRepository.findByIdAndUserId(conversationId, userId)
                .map(this::toConversation);
    }

    @Override
    public List<Conversation> findByUser(UUID userId, int limit, int offset) {
        return conversationJpaRepository.findByUserId(userId, limit, offset).stream()
                .map(this::toConversation)
                .toList();
    }

    @Override
    public Message append(UUID conversationId, Message message) {
        MessageEntity entity = MessageEntity.builder()
                .id(UUID.randomUUID())
                .conversationId(conversationId)
                .role(message.getRole())
                .contenido(message.getContenido())
                .adjuntos(toAttachmentJson(message.getAdjuntos()))
                .toolName(message.getToolName())
                .toolArgs(message.getToolArgs())
                .toolResult(message.getToolResult())
                .build();
        return toMessage(messageJpaRepository.save(entity));
    }

    @Override
    public List<Message> findRecentMessages(UUID conversationId, int limit) {
        List<MessageEntity> descending = messageJpaRepository.findRecentByConversationId(conversationId, limit);
        List<Message> ascending = new ArrayList<>(descending.stream().map(this::toMessage).toList());
        Collections.reverse(ascending);
        return ascending;
    }

    private Conversation toConversation(ConversationEntity entity) {
        return Conversation.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .titulo(entity.getTitulo())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private Message toMessage(MessageEntity entity) {
        return Message.builder()
                .id(entity.getId())
                .conversationId(entity.getConversationId())
                .role(entity.getRole())
                .contenido(entity.getContenido())
                .adjuntos(toMessageAttachment(entity.getAdjuntos()))
                .toolName(entity.getToolName())
                .toolArgs(entity.getToolArgs())
                .toolResult(entity.getToolResult())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private List<AttachmentJson> toAttachmentJson(List<MessageAttachment> attachments) {
        if (attachments == null) {
            return List.of();
        }
        return attachments.stream()
                .map(attachment -> AttachmentJson.builder()
                        .objectPath(attachment.objectPath())
                        .mimeType(attachment.mimeType())
                        .build())
                .toList();
    }

    private List<MessageAttachment> toMessageAttachment(List<AttachmentJson> attachments) {
        if (attachments == null) {
            return List.of();
        }
        return attachments.stream()
                .map(attachment -> new MessageAttachment(attachment.getObjectPath(), attachment.getMimeType()))
                .toList();
    }
}
