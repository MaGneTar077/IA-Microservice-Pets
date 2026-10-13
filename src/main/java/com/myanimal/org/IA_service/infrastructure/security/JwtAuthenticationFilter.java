package com.myanimal.org.IA_service.infrastructure.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.infrastructure.adapters.in.dto.ErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    // Fijo desde la etapa 3.5: ya no es security.jwt.user-id-claim configurable. sub ahora
    // vale lo mismo que id, pero id es el que siempre existió y el brief no pidió cambiarlo.
    private static final String USER_ID_CLAIM = "id";

    private final JwtDecoder jwtDecoder;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtDecoder jwtDecoder, ObjectMapper objectMapper) {
        this.jwtDecoder = jwtDecoder;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/actuator/health")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            log.warn("Falta el header Authorization o no viene con el prefijo Bearer");
            writeUnauthorized(response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException ex) {
            // ex.getMessage() describe qué falló (firma, iss, aud, exp); nunca incluye el
            // token — no hace falta sanitizarlo aparte.
            log.warn("Token JWT inválido: {}", ex.getMessage());
            writeUnauthorized(response);
            return;
        }

        String claimValue = jwt.getClaimAsString(USER_ID_CLAIM);
        UUID userId;
        try {
            userId = UUID.fromString(claimValue);
        } catch (IllegalArgumentException | NullPointerException ex) {
            log.warn("El claim '{}' no trae un UUID válido: '{}'", USER_ID_CLAIM, claimValue);
            writeUnauthorized(response);
            return;
        }

        UserContext userContext = UserContext.builder()
                .userId(userId)
                .username(jwt.getClaimAsString("username"))
                .rawJwt(token)
                .email(jwt.getClaimAsString("email"))
                .platformRole(jwt.getClaimAsString("platform_role"))
                .ctx(jwt.getClaimAsString("ctx"))
                .vetId(parseOptionalUuid(jwt.getClaimAsString("vet_id")))
                .vetRole(jwt.getClaimAsString("vet_role"))
                .vetLicensed(jwt.getClaimAsBoolean("vet_licensed"))
                .build();

        var authentication = new UsernamePasswordAuthenticationToken(userContext, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    /**
     * vet_id solo viene en tokens de clínica; null es el caso normal para un token de
     * usuario, no un error. Un vet_id presente pero mal formado no debería tumbar la
     * autenticación completa (los claims de clínica todavía no se usan para nada).
     */
    private UUID parseOptionalUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            log.warn("El claim 'vet_id' no trae un UUID válido: '{}'", value);
            return null;
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                new ErrorResponse("UNAUTHORIZED", "Token inválido o ausente.")));
    }
}
