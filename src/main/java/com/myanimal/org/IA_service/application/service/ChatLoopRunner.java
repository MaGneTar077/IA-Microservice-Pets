package com.myanimal.org.IA_service.application.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.application.dto.PendingActionDto;
import com.myanimal.org.IA_service.domain.exception.UpstreamSessionExpiredException;
import com.myanimal.org.IA_service.domain.model.AiFunctionCall;
import com.myanimal.org.IA_service.domain.model.AiMessage;
import com.myanimal.org.IA_service.domain.model.AiPart;
import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.model.AiRole;
import com.myanimal.org.IA_service.domain.model.Message;
import com.myanimal.org.IA_service.domain.model.MessageAttachment;
import com.myanimal.org.IA_service.domain.model.MessageRole;
import com.myanimal.org.IA_service.domain.model.PendingAction;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;
import com.myanimal.org.IA_service.domain.ports.out.FileStoragePort;
import com.myanimal.org.IA_service.domain.ports.out.PendingActionRepositoryPort;
import com.myanimal.org.IA_service.infrastructure.config.AppProperties;
import com.myanimal.org.IA_service.infrastructure.config.GeminiProperties;
import com.myanimal.org.IA_service.infrastructure.tools.AiTool;
import com.myanimal.org.IA_service.infrastructure.tools.ToolExecutor;
import com.myanimal.org.IA_service.infrastructure.tools.ToolRegistry;
import com.myanimal.org.IA_service.infrastructure.tools.ToolResult;

import lombok.extern.slf4j.Slf4j;

/**
 * El ciclo de function calling (etapa 3, parte B2). Compartido entre un mensaje de chat
 * nuevo y la confirmación de una pending action: ambos terminan delegando aquí después de
 * su propio paso inicial (guardar el turno del usuario / marcar la acción confirmada).
 */
@Slf4j
@Component
public class ChatLoopRunner {

    private static final DateTimeFormatter TITLE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String SESSION_EXPIRED_MESSAGE =
            "Tu sesión expiró. Por favor inicia sesión de nuevo para continuar.";
    private static final String ITERATIONS_EXHAUSTED_MESSAGE =
            "No pude completar tu solicitud en este momento. ¿Puedes intentarlo de nuevo o darme más detalles?";

    private final AiModelPort aiModelPort;
    private final ConversationRepositoryPort conversationRepositoryPort;
    private final FileStoragePort fileStoragePort;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final PendingActionRepositoryPort pendingActionRepositoryPort;
    private final GeminiProperties geminiProperties;
    private final AppProperties appProperties;
    private final Clock clock;
    private final String systemPromptTemplate;

    public ChatLoopRunner(AiModelPort aiModelPort, ConversationRepositoryPort conversationRepositoryPort,
            FileStoragePort fileStoragePort, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
            PendingActionRepositoryPort pendingActionRepositoryPort, GeminiProperties geminiProperties,
            AppProperties appProperties, Clock clock,
            @Qualifier("systemPromptText") String systemPromptTemplate) {
        this.aiModelPort = aiModelPort;
        this.conversationRepositoryPort = conversationRepositoryPort;
        this.fileStoragePort = fileStoragePort;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.pendingActionRepositoryPort = pendingActionRepositoryPort;
        this.geminiProperties = geminiProperties;
        this.appProperties = appProperties;
        this.clock = clock;
        this.systemPromptTemplate = systemPromptTemplate;
    }

    public ChatResult run(UserContext userContext, UUID conversationId) {
        for (int iteration = 1; iteration <= geminiProperties.getMaxToolIterations(); iteration++) {
            List<Message> history = conversationRepositoryPort.findRecentMessages(
                    conversationId, geminiProperties.getMaxHistoryMessages());
            AiRequest request = buildAiRequest(history, userContext);
            AiResponse response = aiModelPort.generate(request);

            List<AiFunctionCall> calls = response.getFunctionCalls();
            if (calls == null || calls.isEmpty()) {
                return finalizeWithText(conversationId, response);
            }

            AiFunctionCall confirmationRequired = null;
            for (AiFunctionCall call : calls) {
                Optional<AiTool> tool = toolRegistry.findByName(call.name());
                boolean requiresConfirmation = tool.map(AiTool::requiresConfirmation).orElse(false);
                if (requiresConfirmation) {
                    confirmationRequired = call;
                    break;
                }

                ToolResult result;
                try {
                    result = toolExecutor.execute(call.name(), call.args(), userContext);
                } catch (UpstreamSessionExpiredException ex) {
                    log.warn("Sesión expirada durante el loop de tools: {}", ex.getMessage());
                    return sessionExpiredResult(conversationId);
                }
                persistToolMessage(conversationId, call, result);
            }

            if (confirmationRequired != null) {
                return createPendingActionResult(conversationId, userContext, confirmationRequired, response);
            }
            // Todas las llamadas de esta vuelta eran de solo lectura y ya se ejecutaron: reintentar.
        }

        return iterationsExhaustedResult(conversationId);
    }

    private ChatResult finalizeWithText(UUID conversationId, AiResponse response) {
        persistModelText(conversationId, response.getText());
        return ChatResult.builder()
                .conversationId(conversationId)
                .reply(response.getText())
                .model(response.getModelUsed())
                .build();
    }

    private ChatResult sessionExpiredResult(UUID conversationId) {
        persistModelText(conversationId, SESSION_EXPIRED_MESSAGE);
        return ChatResult.builder()
                .conversationId(conversationId)
                .reply(SESSION_EXPIRED_MESSAGE)
                .build();
    }

    private ChatResult iterationsExhaustedResult(UUID conversationId) {
        persistModelText(conversationId, ITERATIONS_EXHAUSTED_MESSAGE);
        return ChatResult.builder()
                .conversationId(conversationId)
                .reply(ITERATIONS_EXHAUSTED_MESSAGE)
                .build();
    }

    private ChatResult createPendingActionResult(UUID conversationId, UserContext userContext,
            AiFunctionCall call, AiResponse response) {
        PendingAction pending = pendingActionRepositoryPort.create(
                conversationId, userContext.userId(), call.name(), call.args());

        String descripcion = toolRegistry.findByName(call.name())
                .map(tool -> tool.describe(call.args()))
                .orElse(call.name());

        String reply = (response.getText() != null && !response.getText().isBlank())
                ? response.getText()
                : "¿Confirmas esta acción?";
        persistModelText(conversationId, reply);

        PendingActionDto pendingActionDto = PendingActionDto.builder()
                .id(pending.getId())
                .tool(pending.getToolName())
                .descripcion(descripcion)
                .args(call.args())
                .expiresAt(pending.getExpiresAt())
                .build();

        return ChatResult.builder()
                .conversationId(conversationId)
                .reply(reply)
                .model(response.getModelUsed())
                .pendingAction(pendingActionDto)
                .build();
    }

    private void persistModelText(UUID conversationId, String text) {
        Message modelTurn = Message.builder()
                .role(MessageRole.MODEL)
                .contenido(text)
                .adjuntos(List.of())
                .build();
        conversationRepositoryPort.append(conversationId, modelTurn);
    }

    private void persistToolMessage(UUID conversationId, AiFunctionCall call, ToolResult result) {
        Map<String, Object> toolArgsEnvelope = new LinkedHashMap<>();
        toolArgsEnvelope.put("callId", call.id());
        if (call.thoughtSignature() != null) {
            toolArgsEnvelope.put("thoughtSignature", call.thoughtSignature());
        }
        toolArgsEnvelope.put("arguments", call.args());

        Map<String, Object> toolResultEnvelope = new LinkedHashMap<>();
        toolResultEnvelope.put("ok", result.ok());
        if (result.ok()) {
            toolResultEnvelope.put("data", result.data());
        } else {
            toolResultEnvelope.put("error", result.errorMessage());
        }

        Message toolMessage = Message.builder()
                .role(MessageRole.TOOL)
                .toolName(call.name())
                .toolArgs(toolArgsEnvelope)
                .toolResult(toolResultEnvelope)
                .adjuntos(List.of())
                .build();
        conversationRepositoryPort.append(conversationId, toolMessage);
    }

    private AiRequest buildAiRequest(List<Message> history, UserContext userContext) {
        int size = history.size();
        int attachmentBoundary = Math.max(0, size - geminiProperties.getMaxHistoryAttachments());

        List<AiMessage> messages = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Message message = history.get(i);
            if (message.getRole() == MessageRole.TOOL) {
                messages.addAll(toAiMessages(message));
                continue;
            }
            if (message.getRole() == MessageRole.USER || message.getRole() == MessageRole.MODEL) {
                messages.add(toAiMessage(message, i >= attachmentBoundary));
            }
        }

        return AiRequest.builder()
                .systemInstruction(renderSystemPrompt(userContext))
                .messages(messages)
                .tools(toolRegistry.declarations())
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<AiMessage> toAiMessages(Message toolMessage) {
        Map<String, Object> argsEnvelope = toolMessage.getToolArgs();
        Map<String, Object> resultEnvelope = toolMessage.getToolResult();
        String callId = (String) argsEnvelope.get("callId");
        String thoughtSignature = (String) argsEnvelope.get("thoughtSignature");
        Map<String, Object> arguments = (Map<String, Object>) argsEnvelope.get("arguments");

        AiMessage modelCallTurn = AiMessage.builder()
                .role(AiRole.MODEL)
                .parts(List.of(AiPart.ofFunctionCall(callId, toolMessage.getToolName(), arguments, thoughtSignature)))
                .build();
        AiMessage responseTurn = AiMessage.builder()
                .role(AiRole.USER)
                .parts(List.of(AiPart.ofFunctionResponse(callId, toolMessage.getToolName(), resultEnvelope)))
                .build();
        return List.of(modelCallTurn, responseTurn);
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

    String renderSystemPrompt(UserContext userContext) {
        ZoneId zone = ZoneId.of(appProperties.getTimezone());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        String dayName = today.getDayOfWeek().getDisplayName(TextStyle.FULL, new Locale("es", "ES"));
        String username = userContext.username() != null ? userContext.username() : "el usuario";

        return systemPromptTemplate
                .replace("{fecha}", today.format(TITLE_DATE_FORMAT))
                .replace("{dia_semana}", dayName)
                .replace("{username}", username);
    }
}
