package com.myanimal.org.IA_service.application.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
import com.myanimal.org.IA_service.domain.model.MessageRole;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.ChatUseCase;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;
import com.myanimal.org.IA_service.infrastructure.config.GeminiProperties;

@Service
public class ChatService implements ChatUseCase {

    private static final DateTimeFormatter TITLE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int TITLE_MAX_LENGTH = 60;

    private final AiModelPort aiModelPort;
    private final ConversationRepositoryPort conversationRepositoryPort;
    private final GeminiProperties geminiProperties;
    private final String systemPrompt;

    public ChatService(AiModelPort aiModelPort, ConversationRepositoryPort conversationRepositoryPort,
            GeminiProperties geminiProperties, @Qualifier("systemPromptText") String systemPrompt) {
        this.aiModelPort = aiModelPort;
        this.conversationRepositoryPort = conversationRepositoryPort;
        this.geminiProperties = geminiProperties;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public ChatResult chat(UserContext userContext, UUID conversationId, String userMessage) {
        // Paso 1 (transacción corta): resolver/crear la conversación y guardar el turno del usuario.
        UUID resolvedConversationId = resolveConversation(userContext, conversationId, userMessage);
        Message userTurn = Message.builder()
                .role(MessageRole.USER)
                .contenido(userMessage)
                .adjuntos(List.of())
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
        List<AiMessage> messages = history.stream()
                .filter(message -> message.getRole() == MessageRole.USER || message.getRole() == MessageRole.MODEL)
                .map(this::toAiMessage)
                .toList();

        return AiRequest.builder()
                .systemInstruction(systemPrompt)
                .messages(messages)
                .build();
    }

    private AiMessage toAiMessage(Message message) {
        AiRole role = message.getRole() == MessageRole.MODEL ? AiRole.MODEL : AiRole.USER;
        return AiMessage.builder()
                .role(role)
                .parts(List.of(AiPart.builder().text(message.getContenido()).build()))
                .build();
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
