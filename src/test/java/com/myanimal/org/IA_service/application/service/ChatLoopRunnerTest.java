package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.exception.UpstreamSessionExpiredException;
import com.myanimal.org.IA_service.domain.model.AiFunctionCall;
import com.myanimal.org.IA_service.domain.model.AiMessage;
import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.model.AiRole;
import com.myanimal.org.IA_service.domain.model.Message;
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

@ExtendWith(MockitoExtension.class)
class ChatLoopRunnerTest {

    private static final String SYSTEM_PROMPT_TEMPLATE = "Hoy es {fecha} ({dia_semana}). Usuario: {username}.";

    @Mock
    private AiModelPort aiModelPort;

    @Mock
    private ConversationRepositoryPort conversationRepositoryPort;

    @Mock
    private FileStoragePort fileStoragePort;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private ToolExecutor toolExecutor;

    @Mock
    private PendingActionRepositoryPort pendingActionRepositoryPort;

    private GeminiProperties geminiProperties(int maxToolIterations) {
        GeminiProperties properties = new GeminiProperties();
        properties.setMaxHistoryMessages(20);
        properties.setMaxHistoryAttachments(2);
        properties.setMaxToolIterations(maxToolIterations);
        return properties;
    }

    private AppProperties appProperties() {
        AppProperties properties = new AppProperties();
        properties.setTimezone("America/Bogota");
        return properties;
    }

    private ChatLoopRunner runner(int maxToolIterations) {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneOffset.UTC);
        return new ChatLoopRunner(aiModelPort, conversationRepositoryPort, fileStoragePort, toolRegistry,
                toolExecutor, pendingActionRepositoryPort, geminiProperties(maxToolIterations), appProperties(),
                fixedClock, SYSTEM_PROMPT_TEMPLATE);
    }

    private Message userMessage(String text) {
        return Message.builder().role(MessageRole.USER).contenido(text).adjuntos(List.of()).build();
    }

    @Test
    void unaVueltaFunctionCallEjecucionYTextoFinal() {
        UUID conversationId = UUID.randomUUID();
        UserContext userContext = new UserContext(UUID.randomUUID(), "Ana", "jwt");

        AiTool tool = mock(AiTool.class);
        when(tool.requiresConfirmation()).thenReturn(false);
        when(toolRegistry.findByName("mi_tool")).thenReturn(Optional.of(tool));
        when(toolRegistry.declarations()).thenReturn(List.of());
        when(conversationRepositoryPort.findRecentMessages(eq(conversationId), anyInt()))
                .thenReturn(List.of(userMessage("hola")));

        AiFunctionCall call = new AiFunctionCall("call_1", "mi_tool", Map.of(), null);
        AiResponse firstResponse = AiResponse.builder().functionCalls(List.of(call)).modelUsed("gemini-test").build();
        AiResponse secondResponse = AiResponse.builder()
                .functionCalls(List.of()).text("listo").modelUsed("gemini-test").build();
        when(aiModelPort.generate(any())).thenReturn(firstResponse, secondResponse);
        when(toolExecutor.execute(eq("mi_tool"), any(), eq(userContext)))
                .thenReturn(new ToolResult(true, Map.of("x", 1), null));

        ChatResult result = runner(5).run(userContext, conversationId);

        assertThat(result.getReply()).isEqualTo("listo");
        assertThat(result.getPendingAction()).isNull();
        verify(aiModelPort, times(2)).generate(any());
        verify(conversationRepositoryPort).append(eq(conversationId),
                argThat(m -> m.getRole() == MessageRole.TOOL && "mi_tool".equals(m.getToolName())));
        verify(conversationRepositoryPort).append(eq(conversationId),
                argThat(m -> m.getRole() == MessageRole.MODEL && "listo".equals(m.getContenido())));
    }

    @Test
    void elIdYElThoughtSignatureDelFunctionCallViajanDeVueltaAlReconstruirElHistorial() {
        UUID conversationId = UUID.randomUUID();
        UserContext userContext = new UserContext(UUID.randomUUID(), "Ana", "jwt");

        AiTool tool = mock(AiTool.class);
        when(tool.requiresConfirmation()).thenReturn(false);
        when(toolRegistry.findByName("mi_tool")).thenReturn(Optional.of(tool));
        when(toolRegistry.declarations()).thenReturn(List.of());

        AiFunctionCall call = new AiFunctionCall("call_42", "mi_tool", Map.of("a", 1), "signature-xyz");
        AiResponse firstResponse = AiResponse.builder().functionCalls(List.of(call)).modelUsed("gemini-test").build();
        AiResponse secondResponse = AiResponse.builder()
                .functionCalls(List.of()).text("listo").modelUsed("gemini-test").build();
        when(aiModelPort.generate(any())).thenReturn(firstResponse, secondResponse);
        when(toolExecutor.execute(eq("mi_tool"), any(), eq(userContext)))
                .thenReturn(new ToolResult(true, Map.of("ok", true), null));

        Message originalUserMessage = userMessage("hola");
        when(conversationRepositoryPort.findRecentMessages(eq(conversationId), anyInt()))
                .thenReturn(List.of(originalUserMessage));

        runner(5).run(userContext, conversationId);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationRepositoryPort, times(2)).append(eq(conversationId), messageCaptor.capture());
        Message persistedToolMessage = messageCaptor.getAllValues().get(0);
        assertThat(persistedToolMessage.getRole()).isEqualTo(MessageRole.TOOL);
        assertThat(persistedToolMessage.getToolArgs().get("callId")).isEqualTo("call_42");
        assertThat(persistedToolMessage.getToolArgs().get("thoughtSignature")).isEqualTo("signature-xyz");

        // Simulamos una vuelta siguiente donde el historial ya trae, tal cual quedó
        // persistido, ese turno de tool — y comprobamos que se reconstruye como el par
        // functionCall (con su id y thoughtSignature) + functionResponse.
        reset(aiModelPort);
        when(aiModelPort.generate(any())).thenReturn(secondResponse);
        when(conversationRepositoryPort.findRecentMessages(eq(conversationId), anyInt()))
                .thenReturn(List.of(originalUserMessage, persistedToolMessage));

        runner(5).run(userContext, conversationId);

        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiModelPort).generate(requestCaptor.capture());
        List<AiMessage> messages = requestCaptor.getValue().getMessages();

        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).getRole()).isEqualTo(AiRole.MODEL);
        assertThat(messages.get(1).getParts().get(0).getFunctionCallId()).isEqualTo("call_42");
        assertThat(messages.get(1).getParts().get(0).getThoughtSignature()).isEqualTo("signature-xyz");
        assertThat(messages.get(2).getRole()).isEqualTo(AiRole.USER);
        assertThat(messages.get(2).getParts().get(0).getFunctionResponseId()).isEqualTo("call_42");
    }

    @Test
    void unToolQueRequiereConfirmacionDetieneElLoopYCreaLaPendingAction() {
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserContext userContext = new UserContext(userId, "Ana", "jwt");

        AiTool tool = mock(AiTool.class);
        when(tool.requiresConfirmation()).thenReturn(true);
        when(tool.describe(any())).thenReturn("Crear mascota Luna");
        when(toolRegistry.findByName("crear_mascota")).thenReturn(Optional.of(tool));
        when(toolRegistry.declarations()).thenReturn(List.of());
        when(conversationRepositoryPort.findRecentMessages(eq(conversationId), anyInt())).thenReturn(List.of());

        AiFunctionCall call = new AiFunctionCall("call_1", "crear_mascota", Map.of("name", "Luna"), null);
        AiResponse response = AiResponse.builder()
                .functionCalls(List.of(call))
                .text("Voy a crear a Luna. ¿Confirmas?")
                .modelUsed("gemini-test")
                .build();
        when(aiModelPort.generate(any())).thenReturn(response);

        UUID pendingId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-09-29T20:00:00Z");
        when(pendingActionRepositoryPort.create(conversationId, userId, "crear_mascota", Map.of("name", "Luna")))
                .thenReturn(PendingAction.builder().id(pendingId).toolName("crear_mascota").expiresAt(expiresAt).build());

        ChatResult result = runner(5).run(userContext, conversationId);

        assertThat(result.getPendingAction()).isNotNull();
        assertThat(result.getPendingAction().getId()).isEqualTo(pendingId);
        assertThat(result.getPendingAction().getTool()).isEqualTo("crear_mascota");
        assertThat(result.getPendingAction().getDescripcion()).isEqualTo("Crear mascota Luna");
        assertThat(result.getPendingAction().getExpiresAt()).isEqualTo(expiresAt);
        assertThat(result.getReply()).isEqualTo("Voy a crear a Luna. ¿Confirmas?");

        verify(aiModelPort, times(1)).generate(any());
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void topeDeIteracionesAlcanzadoDevuelveMensajeAmigableNoExcepcion() {
        UUID conversationId = UUID.randomUUID();
        UserContext userContext = new UserContext(UUID.randomUUID(), "Ana", "jwt");

        AiTool tool = mock(AiTool.class);
        when(tool.requiresConfirmation()).thenReturn(false);
        when(toolRegistry.findByName("mi_tool")).thenReturn(Optional.of(tool));
        when(toolRegistry.declarations()).thenReturn(List.of());
        when(conversationRepositoryPort.findRecentMessages(eq(conversationId), anyInt())).thenReturn(List.of());
        when(toolExecutor.execute(eq("mi_tool"), any(), eq(userContext)))
                .thenReturn(new ToolResult(true, Map.of(), null));

        AiFunctionCall call = new AiFunctionCall("call_x", "mi_tool", Map.of(), null);
        AiResponse alwaysCallsTool = AiResponse.builder().functionCalls(List.of(call)).modelUsed("gemini-test").build();
        when(aiModelPort.generate(any())).thenReturn(alwaysCallsTool);

        ChatResult result = runner(3).run(userContext, conversationId);

        assertThat(result.getReply()).contains("No pude completar");
        verify(aiModelPort, times(3)).generate(any());
    }

    @Test
    void unA401DeUnServicioDestinoCortaElLoopYRespondeQueLaSesionExpiro() {
        UUID conversationId = UUID.randomUUID();
        UserContext userContext = new UserContext(UUID.randomUUID(), "Ana", "jwt");

        AiTool tool = mock(AiTool.class);
        when(tool.requiresConfirmation()).thenReturn(false);
        when(toolRegistry.findByName("listar_mascotas")).thenReturn(Optional.of(tool));
        when(toolRegistry.declarations()).thenReturn(List.of());
        when(conversationRepositoryPort.findRecentMessages(eq(conversationId), anyInt())).thenReturn(List.of());

        AiFunctionCall call = new AiFunctionCall("call_1", "listar_mascotas", Map.of(), null);
        AiResponse response = AiResponse.builder().functionCalls(List.of(call)).modelUsed("gemini-test").build();
        when(aiModelPort.generate(any())).thenReturn(response);
        when(toolExecutor.execute(eq("listar_mascotas"), any(), eq(userContext)))
                .thenThrow(new UpstreamSessionExpiredException("expiró", new RuntimeException("401")));

        ChatResult result = runner(5).run(userContext, conversationId);

        assertThat(result.getReply()).contains("sesión expiró");
        assertThat(result.getPendingAction()).isNull();
        verify(aiModelPort, times(1)).generate(any());
        verify(conversationRepositoryPort).append(eq(conversationId),
                argThat(m -> m.getRole() == MessageRole.MODEL && m.getContenido().contains("sesión expiró")));
    }

    @Test
    void renderSystemPromptSustituyeFechaDiaDeLaSemanaYUsername() {
        String rendered = runner(5).renderSystemPrompt(new UserContext(UUID.randomUUID(), "Ana", "jwt"));

        assertThat(rendered).isEqualTo("Hoy es 29/09/2026 (martes). Usuario: Ana.");
    }

    @Test
    void renderSystemPromptUsaUnFallbackSiElUsernameEsNulo() {
        String rendered = runner(5).renderSystemPrompt(new UserContext(UUID.randomUUID(), null, "jwt"));

        assertThat(rendered).contains("Usuario: el usuario.");
    }
}
