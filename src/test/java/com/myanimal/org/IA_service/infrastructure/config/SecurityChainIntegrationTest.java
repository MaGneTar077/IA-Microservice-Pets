package com.myanimal.org.IA_service.infrastructure.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.myanimal.org.IA_service.domain.ports.in.ChatUseCase;
import com.myanimal.org.IA_service.infrastructure.adapters.in.AiChatController;
import com.myanimal.org.IA_service.infrastructure.security.JwtProperties;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

/**
 * A diferencia de JwtAuthenticationFilterTest (que prueba el filtro aislado, instanciado a
 * mano), este test levanta la cadena REAL de Spring Security (SecurityConfig + el bean real
 * de JwtAuthenticationFilter) para comprobar que el filtro está bien enganchado en el chain,
 * no solo que funciona por su cuenta.
 */
@WebMvcTest(AiChatController.class)
@Import({ SecurityConfig.class, JwtProperties.class })
@ActiveProfiles("test")
class SecurityChainIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatUseCase chatUseCase;

    @MockitoBean
    private UserContextProvider userContextProvider;

    @Test
    void sinAuthorizationDevuelve401() throws Exception {
        mockMvc.perform(post("/api/ai/chat/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hola\"}"))
                .andExpect(status().isUnauthorized());
    }
}
