package com.myanimal.org.IA_service.infrastructure.adapters.in;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

@WebMvcTest(ConversationsController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ConversationsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationQueryUseCase conversationQueryUseCase;

    @MockitoBean
    private UserContextProvider userContextProvider;

    // Etapa 3.5: la validación es contra un JWKS, no un secreto compartido — se mockea
    // JwtDecoder para pasar el filtro sin firmar un JWT real. Ver la misma nota en
    // ActionsControllerTest.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID userId = UUID.randomUUID();
    private final String bearerToken = "Bearer fake-token";

    @BeforeEach
    void setUp() {
        when(userContextProvider.current()).thenReturn(new UserContext(userId, "Ana", "fake.jwt.token"));

        Jwt fakeJwt = Jwt.withTokenValue("fake-token")
                .header("alg", "RS256")
                .claim("id", userId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(fakeJwt);
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
