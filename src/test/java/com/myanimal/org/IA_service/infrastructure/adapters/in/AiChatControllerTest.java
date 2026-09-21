package com.myanimal.org.IA_service.infrastructure.adapters.in;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.ports.in.ChatUseCase;
import com.myanimal.org.IA_service.infrastructure.config.SecurityConfig;

@WebMvcTest(AiChatController.class)
@Import(SecurityConfig.class)
class AiChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatUseCase chatUseCase;

    @Test
    void mensajeValidoDevuelve200() throws Exception {
        when(chatUseCase.chat(anyString())).thenReturn(ChatResult.builder()
                .reply("Cada 3 meses, aproximadamente.")
                .model("gemini-test")
                .build());

        mockMvc.perform(post("/api/ai/chat/text")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"¿Cada cuánto debo desparasitar a mi perro?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Cada 3 meses, aproximadamente."))
                .andExpect(jsonPath("$.model").value("gemini-test"));
    }

    @Test
    void mensajeVacioDevuelve400() throws Exception {
        mockMvc.perform(post("/api/ai/chat/text")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
