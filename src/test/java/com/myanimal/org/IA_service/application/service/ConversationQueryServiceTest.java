package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.application.dto.ConversationSummaryDto;
import com.myanimal.org.IA_service.application.dto.MessageDto;
import com.myanimal.org.IA_service.domain.exception.ConversationNotFoundException;
import com.myanimal.org.IA_service.domain.model.Conversation;
import com.myanimal.org.IA_service.domain.model.Message;
import com.myanimal.org.IA_service.domain.model.MessageAttachment;
import com.myanimal.org.IA_service.domain.model.MessageRole;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;
import com.myanimal.org.IA_service.domain.ports.out.FileStoragePort;
import com.myanimal.org.IA_service.infrastructure.config.SupabaseProperties;

@ExtendWith(MockitoExtension.class)
class ConversationQueryServiceTest {

    @Mock
    private ConversationRepositoryPort conversationRepositoryPort;

    @Mock
    private FileStoragePort fileStoragePort;

    private SupabaseProperties supabaseProperties() {
        SupabaseProperties properties = new SupabaseProperties();
        properties.setSignedUrlTtlSeconds(3600);
        return properties;
    }

    private ConversationQueryService service() {
        return new ConversationQueryService(conversationRepositoryPort, fileStoragePort, supabaseProperties());
    }

    @Test
    void listConversationsMapeaATitutloYFechaDeActualizacion() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Instant updatedAt = Instant.now();
        when(conversationRepositoryPort.findByUser(userId, 20, 0)).thenReturn(List.of(
                Conversation.builder().id(conversationId).titulo("Mi conversación").updatedAt(updatedAt).build()));

        List<ConversationSummaryDto> result = service().listConversations(userId, 20, 0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(conversationId);
        assertThat(result.get(0).getTitulo()).isEqualTo("Mi conversación");
        assertThat(result.get(0).getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void limiteMayorA50SeRecortaA50() {
        UUID userId = UUID.randomUUID();
        when(conversationRepositoryPort.findByUser(eq(userId), eq(50), eq(0))).thenReturn(List.of());

        service().listConversations(userId, 999, 0);

        verify(conversationRepositoryPort).findByUser(userId, 50, 0);
    }

    @Test
    void limiteMenorA1UsaElDefaultDe20() {
        UUID userId = UUID.randomUUID();
        when(conversationRepositoryPort.findByUser(eq(userId), eq(20), eq(0))).thenReturn(List.of());

        service().listConversations(userId, 0, 0);

        verify(conversationRepositoryPort).findByUser(userId, 20, 0);
    }

    @Test
    void offsetNegativoSeRecortaACero() {
        UUID userId = UUID.randomUUID();
        when(conversationRepositoryPort.findByUser(eq(userId), eq(20), eq(0))).thenReturn(List.of());

        service().listConversations(userId, 20, -5);

        verify(conversationRepositoryPort).findByUser(userId, 20, 0);
    }

    @Test
    void conversacionDeOtroUsuarioOInexistenteLanza404NuncaLlegaAPedirMensajes() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepositoryPort.findByIdAndUser(conversationId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listMessages(userId, conversationId, 20, 0))
                .isInstanceOf(ConversationNotFoundException.class);

        verify(conversationRepositoryPort, never()).findMessages(any(), anyInt(), anyInt());
    }

    @Test
    void listMessagesDevuelveRolEnMinusculasYAdjuntosComoUrlFirmadaConElTtlConfigurado() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        MessageAttachment attachment = new MessageAttachment("user/foto.jpg", "image/jpeg");
        when(conversationRepositoryPort.findByIdAndUser(conversationId, userId))
                .thenReturn(Optional.of(Conversation.builder().id(conversationId).userId(userId).build()));
        when(conversationRepositoryPort.findMessages(conversationId, 20, 0)).thenReturn(List.of(
                Message.builder()
                        .id(UUID.randomUUID())
                        .role(MessageRole.USER)
                        .contenido("¿qué raza es?")
                        .adjuntos(List.of(attachment))
                        .createdAt(createdAt)
                        .build()));
        when(fileStoragePort.signedUrl(eq("user/foto.jpg"), eq(Duration.ofSeconds(3600))))
                .thenReturn("https://signed.example/foto.jpg");

        List<MessageDto> result = service().listMessages(userId, conversationId, 20, 0);

        assertThat(result).hasSize(1);
        MessageDto dto = result.get(0);
        assertThat(dto.getRol()).isEqualTo("user");
        assertThat(dto.getContenido()).isEqualTo("¿qué raza es?");
        assertThat(dto.getCreatedAt()).isEqualTo(createdAt);
        assertThat(dto.getAdjuntos()).hasSize(1);
        assertThat(dto.getAdjuntos().get(0).getUrl()).isEqualTo("https://signed.example/foto.jpg");
        assertThat(dto.getAdjuntos().get(0).getMimeType()).isEqualTo("image/jpeg");
    }
}
