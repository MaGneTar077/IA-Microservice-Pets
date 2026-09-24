package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.exception.ConversationNotFoundException;
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
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;
import com.myanimal.org.IA_service.domain.ports.out.FileStoragePort;
import com.myanimal.org.IA_service.infrastructure.config.GeminiProperties;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final String SYSTEM_PROMPT = "Eres el asistente de MyAnimaLog.";

    @Mock
    private AiModelPort aiModelPort;

    @Mock
    private ConversationRepositoryPort conversationRepositoryPort;

    @Mock
    private FileStoragePort fileStoragePort;

    private GeminiProperties geminiProperties(int maxHistoryMessages, int maxHistoryAttachments) {
        GeminiProperties properties = new GeminiProperties();
        properties.setMaxHistoryMessages(maxHistoryMessages);
        properties.setMaxHistoryAttachments(maxHistoryAttachments);
        return properties;
    }

    private ChatService chatService(int maxHistoryMessages) {
        return chatService(maxHistoryMessages, 2);
    }

    private ChatService chatService(int maxHistoryMessages, int maxHistoryAttachments) {
        return new ChatService(aiModelPort, conversationRepositoryPort, fileStoragePort,
                geminiProperties(maxHistoryMessages, maxHistoryAttachments), SYSTEM_PROMPT);
    }

    private AiResponse aiResponse(String text) {
        return AiResponse.builder().text(text).modelUsed("gemini-test").build();
    }

    @Test
    void conversacionNuevaSeCreaConTituloDelPrimerMensajeYGuardaAmbosTurnos() {
        UUID userId = UUID.randomUUID();
        UUID newConversationId = UUID.randomUUID();
        when(conversationRepositoryPort.create(eq(userId), eq("¿Cada cuánto debo desparasitar a mi perro?")))
                .thenReturn(Conversation.builder().id(newConversationId).userId(userId).build());
        when(conversationRepositoryPort.findRecentMessages(eq(newConversationId), anyInt())).thenReturn(List.of());
        when(aiModelPort.generate(any())).thenReturn(aiResponse("Cada 3 meses."));

        ChatResult result = chatService(20).chat(
                new UserContext(userId, "jwt"), null, "¿Cada cuánto debo desparasitar a mi perro?", List.of());

        assertThat(result.getConversationId()).isEqualTo(newConversationId);
        assertThat(result.getReply()).isEqualTo("Cada 3 meses.");
        verify(conversationRepositoryPort).create(userId, "¿Cada cuánto debo desparasitar a mi perro?");
        verify(conversationRepositoryPort, times(2)).append(eq(newConversationId), any());
    }

    @Test
    void conversacionExistenteNoCreaUnaNueva() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepositoryPort.findByIdAndUser(conversationId, userId))
                .thenReturn(Optional.of(Conversation.builder().id(conversationId).userId(userId).build()));
        when(conversationRepositoryPort.findRecentMessages(eq(conversationId), anyInt())).thenReturn(List.of());
        when(aiModelPort.generate(any())).thenReturn(aiResponse("listo"));

        ChatResult result = chatService(20).chat(new UserContext(userId, "jwt"), conversationId, "hola", List.of());

        assertThat(result.getConversationId()).isEqualTo(conversationId);
        verify(conversationRepositoryPort, never()).create(any(), any());
    }

    @Test
    void conversacionDeOtroUsuarioOInexistenteLanzaConversationNotFound() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepositoryPort.findByIdAndUser(conversationId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService(20).chat(new UserContext(userId, "jwt"), conversationId, "hola",
                List.of())).isInstanceOf(ConversationNotFoundException.class);

        verify(aiModelPort, never()).generate(any());
        verify(conversationRepositoryPort, never()).append(any(), any());
        verify(fileStoragePort, never()).upload(any(), any(), any());
    }

    @Test
    void elHistorialSeEnviaEnOrdenAscendenteYRespetaLaVentanaConfigurada() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepositoryPort.findByIdAndUser(conversationId, userId))
                .thenReturn(Optional.of(Conversation.builder().id(conversationId).userId(userId).build()));

        List<Message> history = List.of(
                Message.builder().role(MessageRole.USER).contenido("primero").build(),
                Message.builder().role(MessageRole.MODEL).contenido("segundo").build(),
                Message.builder().role(MessageRole.USER).contenido("tercero").build());
        when(conversationRepositoryPort.findRecentMessages(conversationId, 3)).thenReturn(history);
        when(aiModelPort.generate(any())).thenReturn(aiResponse("respuesta"));

        chatService(3, 0).chat(new UserContext(userId, "jwt"), conversationId, "tercero", List.of());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiModelPort).generate(captor.capture());
        AiRequest sentRequest = captor.getValue();

        assertThat(sentRequest.getMessages()).hasSize(3);
        assertThat(sentRequest.getMessages().get(0).getRole()).isEqualTo(AiRole.USER);
        assertThat(sentRequest.getMessages().get(0).getParts().get(0).getText()).isEqualTo("primero");
        assertThat(sentRequest.getMessages().get(1).getRole()).isEqualTo(AiRole.MODEL);
        assertThat(sentRequest.getMessages().get(1).getParts().get(0).getText()).isEqualTo("segundo");
        assertThat(sentRequest.getMessages().get(2).getParts().get(0).getText()).isEqualTo("tercero");
        verify(conversationRepositoryPort).findRecentMessages(conversationId, 3);
    }

    @Test
    void geminiSeLlamaFueraDeLosAppendsNuncaEntreEllosEnvueltoEnUnaSolaOperacion() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepositoryPort.findByIdAndUser(conversationId, userId))
                .thenReturn(Optional.of(Conversation.builder().id(conversationId).userId(userId).build()));
        when(conversationRepositoryPort.findRecentMessages(eq(conversationId), anyInt())).thenReturn(List.of());
        when(aiModelPort.generate(any())).thenReturn(aiResponse("ok"));

        chatService(20).chat(new UserContext(userId, "jwt"), conversationId, "hola", List.of());

        // Cada llamada al puerto es su propia transacción corta (JpaRepository#save por
        // método); lo que este test puede probar sin un PlatformTransactionManager real es
        // que las 3 operaciones ocurren en 3 llamadas separadas y en este orden exacto, con
        // Gemini estrictamente entre los dos "append" y no dentro de ellos.
        InOrder inOrder = inOrder(conversationRepositoryPort, aiModelPort);
        inOrder.verify(conversationRepositoryPort)
                .append(eq(conversationId), argThat(m -> m.getRole() == MessageRole.USER));
        inOrder.verify(aiModelPort).generate(any());
        inOrder.verify(conversationRepositoryPort)
                .append(eq(conversationId), argThat(m -> m.getRole() == MessageRole.MODEL));
    }

    @Test
    void losAdjuntosSeSubenAntesDeGuardarElTurnoYSeGuardaElObjectPathNoUrl() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        byte[] photoBytes = { 1, 2, 3 };
        when(conversationRepositoryPort.findByIdAndUser(conversationId, userId))
                .thenReturn(Optional.of(Conversation.builder().id(conversationId).userId(userId).build()));
        when(fileStoragePort.upload(photoBytes, "image/jpeg", userId))
                .thenReturn(new StoredFile(userId + "/foto.jpg", "image/jpeg"));
        when(conversationRepositoryPort.findRecentMessages(eq(conversationId), anyInt())).thenReturn(List.of());
        when(aiModelPort.generate(any())).thenReturn(aiResponse("Parece un labrador."));

        chatService(20).chat(new UserContext(userId, "jwt"), conversationId, "¿qué raza es?",
                List.of(new UploadedFile(photoBytes, "image/jpeg")));

        InOrder inOrder = inOrder(fileStoragePort, conversationRepositoryPort);
        inOrder.verify(fileStoragePort).upload(photoBytes, "image/jpeg", userId);
        inOrder.verify(conversationRepositoryPort).append(eq(conversationId), argThat(
                m -> m.getAdjuntos().size() == 1
                        && m.getAdjuntos().get(0).objectPath().equals(userId + "/foto.jpg")
                        && m.getAdjuntos().get(0).mimeType().equals("image/jpeg")));
    }

    @Test
    void losAdjuntosDeTurnosViejosSeSustituyenPorElMarcadorTextualSegunLaVentana() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepositoryPort.findByIdAndUser(conversationId, userId))
                .thenReturn(Optional.of(Conversation.builder().id(conversationId).userId(userId).build()));

        MessageAttachment oldPhoto = new MessageAttachment(userId + "/vieja.jpg", "image/jpeg");
        MessageAttachment recentPhoto = new MessageAttachment(userId + "/reciente.jpg", "image/jpeg");
        List<Message> history = List.of(
                Message.builder().role(MessageRole.USER).contenido("¿qué raza es?").adjuntos(List.of(oldPhoto)).build(),
                Message.builder().role(MessageRole.MODEL).contenido("Parece un labrador.").adjuntos(List.of()).build(),
                Message.builder().role(MessageRole.USER).contenido("¿y esta otra foto?").adjuntos(List.of(recentPhoto)).build());
        when(conversationRepositoryPort.findRecentMessages(conversationId, 3)).thenReturn(history);
        byte[] recentBytes = { 9, 9, 9 };
        when(fileStoragePort.download(recentPhoto.objectPath())).thenReturn(recentBytes);
        when(aiModelPort.generate(any())).thenReturn(aiResponse("respuesta"));

        // maxHistoryAttachments=1: solo el turno más reciente (índice 2) conserva el binario real.
        chatService(3, 1).chat(new UserContext(userId, "jwt"), conversationId, "¿y esta otra foto?", List.of());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiModelPort).generate(captor.capture());
        AiRequest sentRequest = captor.getValue();

        // Turno viejo (índice 0): el adjunto se reemplaza por el marcador de texto.
        assertThat(sentRequest.getMessages().get(0).getParts()).hasSize(2);
        assertThat(sentRequest.getMessages().get(0).getParts().get(1).getText())
                .isEqualTo("[el usuario adjuntó una imagen]");
        assertThat(sentRequest.getMessages().get(0).getParts().get(1).getData()).isNull();

        // Turno reciente (índice 2): conserva el binario real, descargado de Storage.
        assertThat(sentRequest.getMessages().get(2).getParts()).hasSize(2);
        assertThat(sentRequest.getMessages().get(2).getParts().get(1).getData()).isEqualTo(recentBytes);
        assertThat(sentRequest.getMessages().get(2).getParts().get(1).getMimeType()).isEqualTo("image/jpeg");

        verify(fileStoragePort, never()).download(oldPhoto.objectPath());
    }
}
