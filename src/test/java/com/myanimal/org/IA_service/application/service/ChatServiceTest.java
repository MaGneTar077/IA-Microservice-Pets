package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.exception.ConversationNotFoundException;
import com.myanimal.org.IA_service.domain.model.Conversation;
import com.myanimal.org.IA_service.domain.model.MessageRole;
import com.myanimal.org.IA_service.domain.model.StoredFile;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;
import com.myanimal.org.IA_service.domain.ports.out.FileStoragePort;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ConversationRepositoryPort conversationRepositoryPort;

    @Mock
    private FileStoragePort fileStoragePort;

    @Mock
    private ChatLoopRunner chatLoopRunner;

    private ChatService chatService() {
        return new ChatService(conversationRepositoryPort, fileStoragePort, chatLoopRunner);
    }

    @Test
    void conversacionNuevaSeCreaConTituloDelPrimerMensajeYDelegaAlLoopRunner() {
        UUID userId = UUID.randomUUID();
        UUID newConversationId = UUID.randomUUID();
        UserContext userContext = new UserContext(userId, "Ana", "jwt");
        when(conversationRepositoryPort.create(eq(userId), eq("¿Cada cuánto debo desparasitar a mi perro?")))
                .thenReturn(Conversation.builder().id(newConversationId).userId(userId).build());
        when(chatLoopRunner.run(userContext, newConversationId)).thenReturn(ChatResult.builder()
                .conversationId(newConversationId)
                .reply("Cada 3 meses.")
                .model("gemini-test")
                .build());

        ChatResult result = chatService().chat(
                userContext, null, "¿Cada cuánto debo desparasitar a mi perro?", List.of());

        assertThat(result.getConversationId()).isEqualTo(newConversationId);
        assertThat(result.getReply()).isEqualTo("Cada 3 meses.");
        verify(conversationRepositoryPort).create(userId, "¿Cada cuánto debo desparasitar a mi perro?");
        verify(conversationRepositoryPort).append(eq(newConversationId), any());
        verify(chatLoopRunner).run(userContext, newConversationId);
    }

    @Test
    void conversacionExistenteNoCreaUnaNueva() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UserContext userContext = new UserContext(userId, "Ana", "jwt");
        when(conversationRepositoryPort.findByIdAndUser(conversationId, userId))
                .thenReturn(Optional.of(Conversation.builder().id(conversationId).userId(userId).build()));
        when(chatLoopRunner.run(userContext, conversationId)).thenReturn(ChatResult.builder()
                .conversationId(conversationId)
                .reply("listo")
                .build());

        ChatResult result = chatService().chat(userContext, conversationId, "hola", List.of());

        assertThat(result.getConversationId()).isEqualTo(conversationId);
        verify(conversationRepositoryPort, never()).create(any(), any());
    }

    @Test
    void conversacionDeOtroUsuarioOInexistenteLanzaConversationNotFoundYNoLlamaAlLoopRunner() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UserContext userContext = new UserContext(userId, "Ana", "jwt");
        when(conversationRepositoryPort.findByIdAndUser(conversationId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService().chat(userContext, conversationId, "hola", List.of()))
                .isInstanceOf(ConversationNotFoundException.class);

        verify(chatLoopRunner, never()).run(any(), any());
        verify(conversationRepositoryPort, never()).append(any(), any());
        verify(fileStoragePort, never()).upload(any(), any(), any());
    }

    @Test
    void losAdjuntosSeSubenAntesDeGuardarElTurnoYSeGuardaElObjectPathNoUrl() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UserContext userContext = new UserContext(userId, "Ana", "jwt");
        byte[] photoBytes = { 1, 2, 3 };
        when(conversationRepositoryPort.findByIdAndUser(conversationId, userId))
                .thenReturn(Optional.of(Conversation.builder().id(conversationId).userId(userId).build()));
        when(fileStoragePort.upload(photoBytes, "image/jpeg", userId))
                .thenReturn(new StoredFile(userId + "/foto.jpg", "image/jpeg"));
        when(chatLoopRunner.run(userContext, conversationId)).thenReturn(ChatResult.builder()
                .conversationId(conversationId)
                .reply("Parece un labrador.")
                .build());

        chatService().chat(userContext, conversationId, "¿qué raza es?",
                List.of(new UploadedFile(photoBytes, "image/jpeg")));

        InOrder inOrder = inOrder(fileStoragePort, conversationRepositoryPort, chatLoopRunner);
        inOrder.verify(fileStoragePort).upload(photoBytes, "image/jpeg", userId);
        inOrder.verify(conversationRepositoryPort).append(eq(conversationId), org.mockito.ArgumentMatchers.argThat(
                m -> m.getRole() == MessageRole.USER
                        && m.getAdjuntos().size() == 1
                        && m.getAdjuntos().get(0).objectPath().equals(userId + "/foto.jpg")));
        inOrder.verify(chatLoopRunner).run(userContext, conversationId);
    }
}
