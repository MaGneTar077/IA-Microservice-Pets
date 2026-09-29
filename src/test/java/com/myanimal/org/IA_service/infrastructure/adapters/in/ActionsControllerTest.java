package com.myanimal.org.IA_service.infrastructure.adapters.in;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.exception.PendingActionExpiredException;
import com.myanimal.org.IA_service.domain.exception.PendingActionNotFoundException;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.PendingActionUseCase;
import com.myanimal.org.IA_service.infrastructure.config.SecurityConfig;
import com.myanimal.org.IA_service.infrastructure.security.JwtProperties;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@WebMvcTest(ActionsController.class)
@Import({ SecurityConfig.class, JwtProperties.class })
@ActiveProfiles("test")
class ActionsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PendingActionUseCase pendingActionUseCase;

    @MockitoBean
    private UserContextProvider userContextProvider;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    private final UUID userId = UUID.randomUUID();
    private String bearerToken;

    @BeforeEach
    void setUp() {
        when(userContextProvider.current()).thenReturn(new UserContext(userId, "Ana", "fake.jwt.token"));

        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        bearerToken = "Bearer " + Jwts.builder()
                .claim("id", userId.toString())
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
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
