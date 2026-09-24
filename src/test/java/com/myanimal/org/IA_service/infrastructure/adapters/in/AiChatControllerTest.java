package com.myanimal.org.IA_service.infrastructure.adapters.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.exception.AiModelUnavailableException;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.ChatUseCase;
import com.myanimal.org.IA_service.infrastructure.adapters.in.attachment.AttachmentProcessor;
import com.myanimal.org.IA_service.infrastructure.config.SecurityConfig;
import com.myanimal.org.IA_service.infrastructure.security.JwtProperties;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * @WebMvcTest incluye automáticamente cualquier bean Filter (nuestro JwtAuthenticationFilter
 * lo es), aunque no se importe explícitamente — por eso el chain real siempre está activo
 * aquí y las peticiones necesitan un Bearer token real, no @WithMockUser.
 */
@WebMvcTest(AiChatController.class)
@Import({ SecurityConfig.class, JwtProperties.class })
@ActiveProfiles("test")
class AiChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatUseCase chatUseCase;

    @MockitoBean
    private UserContextProvider userContextProvider;

    @MockitoBean
    private AttachmentProcessor attachmentProcessor;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private String bearerToken;

    @BeforeEach
    void setUp() {
        when(userContextProvider.current()).thenReturn(new UserContext(userId, "fake.jwt.token"));

        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        bearerToken = "Bearer " + Jwts.builder()
                .claim("id", userId.toString())
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    @Test
    void mensajeValidoDevuelve200() throws Exception {
        when(chatUseCase.chat(any(), isNull(), anyString(), any())).thenReturn(ChatResult.builder()
                .conversationId(conversationId)
                .reply("Cada 3 meses, aproximadamente.")
                .model("gemini-test")
                .build());

        mockMvc.perform(post("/api/ai/chat/text")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"¿Cada cuánto debo desparasitar a mi perro?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.reply").value("Cada 3 meses, aproximadamente."))
                .andExpect(jsonPath("$.model").value("gemini-test"));
    }

    @Test
    void mensajeVacioDevuelve400() throws Exception {
        mockMvc.perform(post("/api/ai/chat/text")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void modeloNoDisponibleDevuelve503YNoUn500Generico() throws Exception {
        when(chatUseCase.chat(any(), isNull(), anyString(), any()))
                .thenThrow(new AiModelUnavailableException("El asistente no está disponible."));

        mockMvc.perform(post("/api/ai/chat/text")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"¿Cada cuánto debo desparasitar a mi perro?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("MODEL_UNAVAILABLE"));
    }

    @Test
    void continuaConversacionExistenteEnviandoElConversationId() throws Exception {
        when(chatUseCase.chat(any(), eq(conversationId), anyString(), any())).thenReturn(ChatResult.builder()
                .conversationId(conversationId)
                .reply("Debería pesar entre 25 y 30 kilos.")
                .model("gemini-test")
                .build());

        mockMvc.perform(post("/api/ai/chat/text")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + conversationId + "\",\"message\":\"¿y cuánto debería pesar?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()));
    }

    @Test
    void sinTokenDevuelve401() throws Exception {
        mockMvc.perform(post("/api/ai/chat/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hola\"}"))
                .andExpect(status().isUnauthorized());
    }
}
