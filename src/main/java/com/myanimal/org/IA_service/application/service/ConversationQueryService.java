package com.myanimal.org.IA_service.application.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.myanimal.org.IA_service.application.dto.AttachmentDto;
import com.myanimal.org.IA_service.application.dto.ConversationSummaryDto;
import com.myanimal.org.IA_service.application.dto.MessageDto;
import com.myanimal.org.IA_service.domain.exception.ConversationNotFoundException;
import com.myanimal.org.IA_service.domain.model.Conversation;
import com.myanimal.org.IA_service.domain.model.Message;
import com.myanimal.org.IA_service.domain.model.MessageAttachment;
import com.myanimal.org.IA_service.domain.ports.in.ConversationQueryUseCase;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;
import com.myanimal.org.IA_service.domain.ports.out.FileStoragePort;
import com.myanimal.org.IA_service.infrastructure.config.SupabaseProperties;

@Service
public class ConversationQueryService implements ConversationQueryUseCase {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final ConversationRepositoryPort conversationRepositoryPort;
    private final FileStoragePort fileStoragePort;
    private final SupabaseProperties supabaseProperties;

    public ConversationQueryService(ConversationRepositoryPort conversationRepositoryPort,
            FileStoragePort fileStoragePort, SupabaseProperties supabaseProperties) {
        this.conversationRepositoryPort = conversationRepositoryPort;
        this.fileStoragePort = fileStoragePort;
        this.supabaseProperties = supabaseProperties;
    }

    @Override
    public List<ConversationSummaryDto> listConversations(UUID userId, int limit, int offset) {
        List<Conversation> conversations = conversationRepositoryPort.findByUser(
                userId, clampLimit(limit), clampOffset(offset));
        return conversations.stream().map(this::toSummaryDto).toList();
    }

    @Override
    public List<MessageDto> listMessages(UUID userId, UUID conversationId, int limit, int offset) {
        conversationRepositoryPort.findByIdAndUser(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(
                        "La conversación no existe o no pertenece al usuario."));

        List<Message> messages = conversationRepositoryPort.findMessages(
                conversationId, clampLimit(limit), clampOffset(offset));
        return messages.stream().map(this::toMessageDto).toList();
    }

    private ConversationSummaryDto toSummaryDto(Conversation conversation) {
        return ConversationSummaryDto.builder()
                .id(conversation.getId())
                .titulo(conversation.getTitulo())
                .updatedAt(conversation.getUpdatedAt())
                .build();
    }

    private MessageDto toMessageDto(Message message) {
        List<MessageAttachment> adjuntos = message.getAdjuntos();
        List<AttachmentDto> attachmentDtos = adjuntos == null ? List.of() : adjuntos.stream()
                .map(this::toAttachmentDto)
                .toList();

        return MessageDto.builder()
                .id(message.getId())
                .rol(message.getRole().name().toLowerCase())
                .contenido(message.getContenido())
                .adjuntos(attachmentDtos)
                .createdAt(message.getCreatedAt())
                .build();
    }

    private AttachmentDto toAttachmentDto(MessageAttachment attachment) {
        Duration ttl = Duration.ofSeconds(supabaseProperties.getSignedUrlTtlSeconds());
        String url = fileStoragePort.signedUrl(attachment.objectPath(), ttl);
        return AttachmentDto.builder()
                .url(url)
                .mimeType(attachment.mimeType())
                .build();
    }

    private int clampLimit(int limit) {
        if (limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int clampOffset(int offset) {
        return Math.max(offset, 0);
    }
}
