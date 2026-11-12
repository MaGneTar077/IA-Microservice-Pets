package com.myanimal.org.IA_service.infrastructure.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myanimal.org.IA_service.infrastructure.adapters.in.dto.InternalErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Autentica únicamente /internal/**: servicio a servicio (Veterinary), sin JWT de usuario.
 * JwtAuthenticationFilter se salta estas rutas (ver su shouldNotFilter).
 */
@Slf4j
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private final String expectedApiKey;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(@Value("${internal.api-key}") String expectedApiKey,
            ObjectMapper objectMapper) {
        this.expectedApiKey = expectedApiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String providedKey = request.getHeader(API_KEY_HEADER);
        if (providedKey == null || !constantTimeEquals(providedKey, expectedApiKey)) {
            log.warn("Falta o es inválido el header {}", API_KEY_HEADER);
            writeUnauthorized(response);
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken("internal-service", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(new InternalErrorResponse(
                Instant.now(), HttpStatus.UNAUTHORIZED.value(), HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "Falta o es inválida la API key interna.", "UNAUTHENTICATED")));
    }
}
