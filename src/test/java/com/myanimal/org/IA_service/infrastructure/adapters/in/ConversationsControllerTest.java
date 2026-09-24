package com.myanimal.org.IA_service.infrastructure.adapters.in;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.myanimal.org.IA_service.application.dto.AttachmentDto;
import com.myanimal.org.IA_service.application.dto.ConversationSummaryDto;
import com.myanimal.org.IA_service.application.dto.MessageDto;
import com.myanimal.org.IA_service.domain.exception.ConversationNotFoundException;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.ConversationQueryUseCase;
import com.myanimal.org.IA_service.infrastructure.config.SecurityConfig;
import com.myanimal.org.IA_service.infrastructure.security.JwtProperties;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@WebMvcTest(ConversationsController.class)
@Import({ SecurityConfig.class, JwtProperties.class })
@ActiveProfiles("test")
class ConversationsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationQueryUseCase conversationQueryUseCase;

    @MockitoBean
    private UserContextProvider userContextProvider;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    private final UUID userId = UUID.randomUUID();
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
    void listaConversacionesDelUsuarioAutenticado() throws Exception {
        UUID conversationId = UUID.randomUUID();
        when(conversationQueryUseCase.listConversations(userId, 20, 0)).thenReturn(List.of(
                ConversationSummaryDto.builder()
                        .id(conversationId)
                        .titulo("¿qué raza es?")
                        .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                        .build()));

        mockMvc.perform(get("/api/ai/conversations").header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(conversationId.toString()))
                .andExpect(jsonPath("$[0].titulo").value("¿qué raza es?"));
    }

    @Test
    void respetaLosParametrosDePaginacion() throws Exception {
        when(conversationQueryUseCase.listConversations(eq(userId), eq(10), eq(5))).thenReturn(List.of());

        mockMvc.perform(get("/api/ai/conversations")
                        .header("Authorization", bearerToken)
                        .param("limit", "10")
                        .param("offset", "5"))
                .andExpect(status().isOk());
    }

    @Test
    void listaMensajesConAdjuntosComoUrlFirmada() throws Exception {
        UUID conversationId = UUID.randomUUID();
        when(conversationQueryUseCase.listMessages(userId, conversationId, 20, 0)).thenReturn(List.of(
                MessageDto.builder()
                        .id(UUID.randomUUID())
                        .rol("user")
                        .contenido("¿qué raza es?")
                        .adjuntos(List.of(AttachmentDto.builder()
                                .url("https://signed.example/foto.jpg")
                                .mimeType("image/jpeg")
                                .build()))
                        .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                        .build()));

        mockMvc.perform(get("/api/ai/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rol").value("user"))
                .andExpect(jsonPath("$[0].adjuntos[0].url").value("https://signed.example/foto.jpg"))
                .andExpect(jsonPath("$[0].adjuntos[0].mimeType").value("image/jpeg"));
    }

    @Test
    void conversacionAjenaOInexistenteDevuelve404NuncaUn403() throws Exception {
        UUID conversationId = UUID.randomUUID();
        when(conversationQueryUseCase.listMessages(userId, conversationId, 20, 0))
                .thenThrow(new ConversationNotFoundException("La conversación no existe o no pertenece al usuario."));

        mockMvc.perform(get("/api/ai/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void sinTokenDevuelve401() throws Exception {
        mockMvc.perform(get("/api/ai/conversations")).andExpect(status().isUnauthorized());
    }
}
