package com.myanimal.org.IA_service.application.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.exception.ConversationNotFoundException;
import com.myanimal.org.IA_service.domain.model.Conversation;
import com.myanimal.org.IA_service.domain.model.Message;
import com.myanimal.org.IA_service.domain.model.MessageAttachment;
import com.myanimal.org.IA_service.domain.model.MessageRole;
import com.myanimal.org.IA_service.domain.model.StoredFile;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.ChatUseCase;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;
import com.myanimal.org.IA_service.domain.ports.out.FileStoragePort;

@Service
public class ChatService implements ChatUseCase {

    private static final DateTimeFormatter TITLE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int TITLE_MAX_LENGTH = 60;

    private final ConversationRepositoryPort conversationRepositoryPort;
    private final FileStoragePort fileStoragePort;
    private final ChatLoopRunner chatLoopRunner;

    public ChatService(ConversationRepositoryPort conversationRepositoryPort, FileStoragePort fileStoragePort,
            ChatLoopRunner chatLoopRunner) {
        this.conversationRepositoryPort = conversationRepositoryPort;
        this.fileStoragePort = fileStoragePort;
        this.chatLoopRunner = chatLoopRunner;
    }

    @Override
    public ChatResult chat(UserContext userContext, UUID conversationId, String userMessage,
            List<UploadedFile> attachments) {
        // Transacción corta: resolver/crear la conversación y guardar el turno del usuario.
        // Los adjuntos se suben a Storage ANTES de abrir esa transacción: si Supabase falla,
        // no queremos una fila de mensaje a medio guardar.
        List<MessageAttachment> storedAttachments = uploadAttachments(attachments, userContext.userId());

        UUID resolvedConversationId = resolveConversation(userContext, conversationId, userMessage);
        Message userTurn = Message.builder()
                .role(MessageRole.USER)
                .contenido(userMessage)
                .adjuntos(storedAttachments)
                .build();
        conversationRepositoryPort.append(resolvedConversationId, userTurn);

        // El resto (llamar a Gemini, ejecutar tools, pedir confirmación, reintentar) es el
        // mismo ciclo que usa la confirmación de una pending action.
        return chatLoopRunner.run(userContext, resolvedConversationId);
    }

    private List<MessageAttachment> uploadAttachments(List<UploadedFile> attachments, UUID userId) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<MessageAttachment> result = new ArrayList<>();
        for (UploadedFile attachment : attachments) {
            StoredFile stored = fileStoragePort.upload(attachment.content(), attachment.mimeType(), userId);
            result.add(new MessageAttachment(stored.objectPath(), stored.mimeType()));
        }
        return result;
    }

    private UUID resolveConversation(UserContext userContext, UUID conversationId, String userMessage) {
        if (conversationId == null) {
            Conversation created = conversationRepositoryPort.create(userContext.userId(), buildTitle(userMessage));
            return created.getId();
        }
        Conversation existing = conversationRepositoryPort.findByIdAndUser(conversationId, userContext.userId())
                .orElseThrow(() -> new ConversationNotFoundException(
                        "La conversación no existe o no pertenece al usuario."));
        return existing.getId();
    }

    private String buildTitle(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "Conversación del " + LocalDate.now().format(TITLE_DATE_FORMAT);
        }
        String trimmed = userMessage.trim();
        if (trimmed.length() <= TITLE_MAX_LENGTH) {
            return trimmed;
        }
        String truncated = trimmed.substring(0, TITLE_MAX_LENGTH);
        int lastSpace = truncated.lastIndexOf(' ');
        return lastSpace > 0 ? truncated.substring(0, lastSpace) : truncated;
    }
}
