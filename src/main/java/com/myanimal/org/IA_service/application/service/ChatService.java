package com.myanimal.org.IA_service.application.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.exception.ConversationNotFoundException;
import com.myanimal.org.IA_service.domain.model.AiMessage;
import com.myanimal.org.IA_service.domain.model.AiPart;
import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.model.AiRole;
import com.myanimal.org.IA_service.domain.model.Conversation;
import com.myanimal.org.IA_service.domain.model.Message;
import com.myanimal.org.IA_service.domain.model.MessageAttachment;
import com.myanimal.org.IA_service.domain.model.MessageRole;
import com.myanimal.org.IA_service.domain.model.StoredFile;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.ChatUseCase;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;
import com.myanimal.org.IA_service.domain.ports.out.FileStoragePort;
import com.myanimal.org.IA_service.infrastructure.config.GeminiProperties;

@Service
public class ChatService implements ChatUseCase {

    private static final DateTimeFormatter TITLE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int TITLE_MAX_LENGTH = 60;

    private final AiModelPort aiModelPort;
    private final ConversationRepositoryPort conversationRepositoryPort;
    private final FileStoragePort fileStoragePort;
    private final GeminiProperties geminiProperties;
    private final String systemPrompt;

    public ChatService(AiModelPort aiModelPort, ConversationRepositoryPort conversationRepositoryPort,
            FileStoragePort fileStoragePort, GeminiProperties geminiProperties,
            @Qualifier("systemPromptText") String systemPrompt) {
        this.aiModelPort = aiModelPort;
        this.conversationRepositoryPort = conversationRepositoryPort;
        this.fileStoragePort = fileStoragePort;
        this.geminiProperties = geminiProperties;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public ChatResult chat(UserContext userContext, UUID conversationId, String userMessage,
            List<UploadedFile> attachments) {
        // Paso 1 (transacción corta): resolver/crear la conversación y guardar el turno del usuario.
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

        // Paso 2 (fuera de transacción): leer historial y llamar a Gemini.
        List<Message> history = conversationRepositoryPort.findRecentMessages(
                resolvedConversationId, geminiProperties.getMaxHistoryMessages());
        AiRequest request = buildAiRequest(history);
        AiResponse response = aiModelPort.generate(request);

        // Paso 3 (transacción corta): guardar la respuesta del modelo.
        Message modelTurn = Message.builder()
                .role(MessageRole.MODEL)
                .contenido(response.getText())
                .adjuntos(List.of())
                .build();
        conversationRepositoryPort.append(resolvedConversationId, modelTurn);

        return ChatResult.builder()
                .conversationId(resolvedConversationId)
                .reply(response.getText())
                .model(response.getModelUsed())
                .build();
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

    private AiRequest buildAiRequest(List<Message> history) {
        int size = history.size();
        int attachmentBoundary = Math.max(0, size - geminiProperties.getMaxHistoryAttachments());

        List<AiMessage> messages = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Message message = history.get(i);
            if (message.getRole() != MessageRole.USER && message.getRole() != MessageRole.MODEL) {
                continue;
            }
            messages.add(toAiMessage(message, i >= attachmentBoundary));
        }

        return AiRequest.builder()
                .systemInstruction(systemPrompt)
                .messages(messages)
                .build();
    }

    private AiMessage toAiMessage(Message message, boolean allowBinaryAttachments) {
        List<AiPart> parts = new ArrayList<>();
        if (message.getContenido() != null && !message.getContenido().isBlank()) {
            parts.add(AiPart.ofText(message.getContenido()));
        }

        List<MessageAttachment> adjuntos = message.getAdjuntos();
        if (adjuntos != null) {
            for (MessageAttachment adjunto : adjuntos) {
                if (allowBinaryAttachments) {
                    byte[] content = fileStoragePort.download(adjunto.objectPath());
                    parts.add(AiPart.ofBinary(adjunto.mimeType(), content));
                } else {
                    parts.add(AiPart.ofText(textMarkerFor(adjunto.mimeType())));
                }
            }
        }

        if (parts.isEmpty()) {
            parts.add(AiPart.ofText(""));
        }

        AiRole role = message.getRole() == MessageRole.MODEL ? AiRole.MODEL : AiRole.USER;
        return AiMessage.builder().role(role).parts(parts).build();
    }

    private String textMarkerFor(String mimeType) {
        if (mimeType != null && mimeType.startsWith("image/")) {
            return "[el usuario adjuntó una imagen]";
        }
        if (mimeType != null && mimeType.startsWith("audio/")) {
            return "[el usuario adjuntó un audio]";
        }
        return "[el usuario adjuntó un archivo]";
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
