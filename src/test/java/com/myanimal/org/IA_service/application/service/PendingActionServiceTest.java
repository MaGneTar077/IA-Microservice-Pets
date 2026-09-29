package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.exception.PendingActionExpiredException;
import com.myanimal.org.IA_service.domain.exception.PendingActionNotFoundException;
import com.myanimal.org.IA_service.domain.model.MessageRole;
import com.myanimal.org.IA_service.domain.model.PendingAction;
import com.myanimal.org.IA_service.domain.model.PendingActionStatus;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;
import com.myanimal.org.IA_service.domain.ports.out.PendingActionRepositoryPort;

@ExtendWith(MockitoExtension.class)
class PendingActionServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneOffset.UTC);

    @Mock
    private PendingActionRepositoryPort pendingActionRepositoryPort;

    @Mock
    private ConversationRepositoryPort conversationRepositoryPort;

    @Mock
    private ChatLoopRunner chatLoopRunner;

    private PendingActionService service() {
        return new PendingActionService(pendingActionRepositoryPort, conversationRepositoryPort, chatLoopRunner,
                FIXED_CLOCK);
    }

    private PendingAction pending(UUID id, UUID conversationId, PendingActionStatus estado, Instant expiresAt) {
        return PendingAction.builder()
                .id(id)
                .conversationId(conversationId)
                .toolName("crear_mascota")
                .toolArgs(Map.of("callId", "call_1", "arguments", Map.of("name", "Luna")))
                .estado(estado)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    void confirmarUnaAccionPendienteYVigenteEjecutaElToolYContinuaElLoop() {
        UUID userId = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        PendingAction action = pending(pendingId, conversationId, PendingActionStatus.PENDIENTE,
                Instant.parse("2026-09-29T20:00:00Z"));
        when(pendingActionRepositoryPort.findByIdAndUser(pendingId, userId)).thenReturn(Optional.of(action));
        when(chatLoopRunner.confirmPendingAction(ctx, action)).thenReturn(ChatResult.builder()
                .conversationId(conversationId).reply("Listo.").build());

        ChatResult result = service().confirm(ctx, pendingId);

        assertThat(result.getReply()).isEqualTo("Listo.");
        verify(pendingActionRepositoryPort).updateStatus(pendingId, PendingActionStatus.CONFIRMADA,
                FIXED_CLOCK.instant());
    }

    @Test
    void siElToolTuvoExitoYGeminiFallaDespuesDevuelve200ConReplyDeRespaldoYQuedaConfirmada() {
        UUID userId = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        PendingAction action = pending(pendingId, conversationId, PendingActionStatus.PENDIENTE,
                Instant.parse("2026-09-29T20:00:00Z"));
        when(pendingActionRepositoryPort.findByIdAndUser(pendingId, userId)).thenReturn(Optional.of(action));
        // ChatLoopRunner ya absorbió la falla de Gemini (el tool sí se ejecutó) y devuelve
        // un reply de respaldo en vez de propagar el error.
        when(chatLoopRunner.confirmPendingAction(ctx, action)).thenReturn(ChatResult.builder()
                .conversationId(conversationId)
                .reply("Listo: Registrar a Luna (Perro, Labrador)")
                .build());

        ChatResult result = service().confirm(ctx, pendingId);

        assertThat(result.getReply()).isEqualTo("Listo: Registrar a Luna (Perro, Labrador)");
        verify(pendingActionRepositoryPort).updateStatus(pendingId, PendingActionStatus.CONFIRMADA,
                FIXED_CLOCK.instant());
        verify(pendingActionRepositoryPort, never()).updateStatus(eq(pendingId), eq(PendingActionStatus.EXPIRADA),
                any());
        verify(pendingActionRepositoryPort, never()).updateStatus(eq(pendingId), eq(PendingActionStatus.RECHAZADA),
                any());
    }

    @Test
    void confirmarUnaAccionVencidaLaMarcaExpiradaYLanza410() {
        UUID userId = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        PendingAction action = pending(pendingId, UUID.randomUUID(), PendingActionStatus.PENDIENTE,
                Instant.parse("2026-09-29T10:00:00Z"));
        when(pendingActionRepositoryPort.findByIdAndUser(pendingId, userId)).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service().confirm(new UserContext(userId, "Ana", "jwt"), pendingId))
                .isInstanceOf(PendingActionExpiredException.class);

        verify(pendingActionRepositoryPort).updateStatus(pendingId, PendingActionStatus.EXPIRADA,
                FIXED_CLOCK.instant());
        verify(chatLoopRunner, never()).confirmPendingAction(any(), any());
    }

    @Test
    void confirmarUnaAccionInexistenteLanza404() {
        UUID userId = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        when(pendingActionRepositoryPort.findByIdAndUser(pendingId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().confirm(new UserContext(userId, "Ana", "jwt"), pendingId))
                .isInstanceOf(PendingActionNotFoundException.class);
    }

    @Test
    void confirmarUnaAccionYaConfirmadaLanza404() {
        UUID userId = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        PendingAction action = pending(pendingId, UUID.randomUUID(), PendingActionStatus.CONFIRMADA,
                Instant.parse("2026-09-29T20:00:00Z"));
        when(pendingActionRepositoryPort.findByIdAndUser(pendingId, userId)).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service().confirm(new UserContext(userId, "Ana", "jwt"), pendingId))
                .isInstanceOf(PendingActionNotFoundException.class);

        verify(pendingActionRepositoryPort, never()).updateStatus(any(), any(), any());
    }

    @Test
    void rechazarNoLlamaAGeminiYPersisteUnMensajeDeCancelacion() {
        UUID userId = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        PendingAction action = pending(pendingId, conversationId, PendingActionStatus.PENDIENTE,
                Instant.parse("2026-09-29T20:00:00Z"));
        when(pendingActionRepositoryPort.findByIdAndUser(pendingId, userId)).thenReturn(Optional.of(action));

        ChatResult result = service().reject(new UserContext(userId, "Ana", "jwt"), pendingId);

        assertThat(result.getConversationId()).isEqualTo(conversationId);
        assertThat(result.getReply()).isNotBlank();
        verify(pendingActionRepositoryPort).updateStatus(pendingId, PendingActionStatus.RECHAZADA,
                FIXED_CLOCK.instant());
        verify(conversationRepositoryPort).append(eq(conversationId),
                argThat(m -> m.getRole() == MessageRole.MODEL));
        verifyNoInteractions(chatLoopRunner);
    }
}
