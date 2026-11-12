package com.myanimal.org.IA_service.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.servlet.FilterChain;

class ApiKeyAuthenticationFilterTest {

    private final ApiKeyAuthenticationFilter filter =
            new ApiKeyAuthenticationFilter("the-real-key", new ObjectMapper().registerModule(new JavaTimeModule()));

    @AfterEach
    void limpiarContextoDeSeguridad() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rutaFueraDeInternalNoSeFiltra() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void rutaInternalSiSeFiltra() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/ai/document-analysis");

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void sinHeaderDevuelve401ConFormatoInterno() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/ai/document-analysis");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void conApiKeyIncorrectaDevuelve401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/ai/document-analysis");
        request.addHeader("X-Internal-Api-Key", "otra-llave");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void conApiKeyCorrectaContinuaLaCadenaYAutentica() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/ai/document-analysis");
        request.addHeader("X-Internal-Api-Key", "the-real-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }
}
