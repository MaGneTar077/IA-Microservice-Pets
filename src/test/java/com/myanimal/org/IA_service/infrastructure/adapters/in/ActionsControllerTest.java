package com.myanimal.org.IA_service.infrastructure.adapters.in;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.exception.PendingActionExpiredException;
import com.myanimal.org.IA_service.domain.exception.PendingActionNotFoundException;
import com.myanimal.org.IA_service.domain.exception.UpstreamSessionExpiredException;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.PendingActionUseCase;
import com.myanimal.org.IA_service.infrastructure.config.SecurityConfig;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

@WebMvcTest(ActionsController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ActionsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PendingActionUseCase pendingActionUseCase;

    @MockitoBean
    private UserContextProvider userContextProvider;

    // El JwtAuthenticationFilter real sigue corriendo en este slice (ver "Trampas
    // conocidas" en CLAUDE.md): se mockea JwtDecoder en vez de JwtProperties/firmar un JWT
    // real, porque desde la etapa 3.5 la validación es contra un JWKS, no un secreto
    // compartido. El UserContext real que construye el filtro no importa para este test:
    // el controller lee el suyo de UserContextProvider, que está mockeado aparte.
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
    void confirmarUnaAccionValidaDevuelveElResultadoDelLoop() throws Exception {
        UUID pendingId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(pendingActionUseCase.confirm(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(pendingId)))
                .thenReturn(ChatResult.builder().conversationId(conversationId).reply("Listo, Luna quedó registrada.")
                        .build());

        mockMvc.perform(post("/api/ai/actions/{id}/confirm", pendingId).header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Listo, Luna quedó registrada."));
    }

    @Test
    void confirmarUnaAccionAjenaOInexistenteDevuelve404() throws Exception {
        UUID pendingId = UUID.randomUUID();
        when(pendingActionUseCase.confirm(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(pendingId)))
                .thenThrow(new PendingActionNotFoundException("La acción no existe."));

        mockMvc.perform(post("/api/ai/actions/{id}/confirm", pendingId).header("Authorization", bearerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PENDING_ACTION_NOT_FOUND"));
    }

    @Test
    void confirmarUnaAccionVencidaDevuelve410() throws Exception {
        UUID pendingId = UUID.randomUUID();
        when(pendingActionUseCase.confirm(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(pendingId)))
                .thenThrow(new PendingActionExpiredException("La acción ya expiró."));

        mockMvc.perform(post("/api/ai/actions/{id}/confirm", pendingId).header("Authorization", bearerToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("PENDING_ACTION_EXPIRED"));
    }

    @Test
    void confirmarUnaAccionCuyoServicioDestinoDa401Devuelve401ConTokenExpired() throws Exception {
        UUID pendingId = UUID.randomUUID();
        when(pendingActionUseCase.confirm(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(pendingId)))
                .thenThrow(new UpstreamSessionExpiredException("Tu sesión expiró, vuelve a intentarlo.",
                        new RuntimeException("401")));

        mockMvc.perform(post("/api/ai/actions/{id}/confirm", pendingId).header("Authorization", bearerToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.message").value("Tu sesión expiró, vuelve a intentarlo."));
    }

    @Test
    void rechazarUnaAccionDevuelveElTextoFijoSinLlamarAGemini() throws Exception {
        UUID pendingId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(pendingActionUseCase.reject(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(pendingId)))
                .thenReturn(ChatResult.builder().conversationId(conversationId).reply("De acuerdo, no realicé esa acción.")
                        .build());

        mockMvc.perform(post("/api/ai/actions/{id}/reject", pendingId).header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("De acuerdo, no realicé esa acción."));
    }

    @Test
    void sinTokenDevuelve401() throws Exception {
        mockMvc.perform(post("/api/ai/actions/{id}/confirm", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
