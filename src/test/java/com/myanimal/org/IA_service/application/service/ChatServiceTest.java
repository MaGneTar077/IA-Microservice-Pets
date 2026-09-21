package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.model.AiRole;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final String SYSTEM_PROMPT = "Eres el asistente de MyAnimaLog.";

    @Mock
    private AiModelPort aiModelPort;

    @Test
    void incluyeInstruccionDeSistemaYMensajeDelUsuario() {
        ChatService chatService = new ChatService(aiModelPort, SYSTEM_PROMPT);

        when(aiModelPort.generate(any())).thenReturn(AiResponse.builder()
                .text("Respuesta de prueba")
                .modelUsed("gemini-test")
                .build());

        ChatResult result = chatService.chat("¿Cada cuánto debo desparasitar a mi perro?");

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiModelPort).generate(captor.capture());

        AiRequest sentRequest = captor.getValue();
        assertThat(sentRequest.getSystemInstruction()).isEqualTo(SYSTEM_PROMPT);
        assertThat(sentRequest.getMessages()).hasSize(1);
        assertThat(sentRequest.getMessages().get(0).getRole()).isEqualTo(AiRole.USER);
        assertThat(sentRequest.getMessages().get(0).getParts().get(0).getText())
                .isEqualTo("¿Cada cuánto debo desparasitar a mi perro?");

        assertThat(result.getReply()).isEqualTo("Respuesta de prueba");
        assertThat(result.getModel()).isEqualTo("gemini-test");
    }
}
