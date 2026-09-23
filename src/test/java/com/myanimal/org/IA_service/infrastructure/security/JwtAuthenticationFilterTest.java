package com.myanimal.org.IA_service.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myanimal.org.IA_service.domain.model.UserContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.slf4j.LoggerFactory;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private JwtProperties properties(String userIdClaim) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setUserIdClaim(userIdClaim);
        return properties;
    }

    private JwtAuthenticationFilter filter(String userIdClaim) {
        return new JwtAuthenticationFilter(properties(userIdClaim), new ObjectMapper());
    }

    private String token(String claimName, String claimValue, Instant issuedAt, Instant expiration, SecretKey key) {
        return Jwts.builder()
                .claim(claimName, claimValue)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiration))
                .signWith(key)
                .compact();
    }

    @Test
    void tokenValidoAutenticaYContinuaLaCadena() throws Exception {
        UUID userId = UUID.randomUUID();
        String jwt = token("id", userId.toString(), Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS), SIGNING_KEY);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat/text");
        request.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter("id").doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        UserContext userContext = (UserContext) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(userContext.userId()).isEqualTo(userId);
        assertThat(userContext.rawJwt()).isEqualTo(jwt);
    }

    @Test
    void tokenExpiradoDevuelve401() throws Exception {
        UUID userId = UUID.randomUUID();
        String jwt = token("id", userId.toString(),
                Instant.now().minus(2, ChronoUnit.HOURS), Instant.now().minus(1, ChronoUnit.HOURS), SIGNING_KEY);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat/text");
        request.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter("id").doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void firmaInvalidaDevuelve401() throws Exception {
        SecretKey otraKey = Keys.hmacShaKeyFor("otra-clave-distinta-de-32-bytes!".getBytes(StandardCharsets.UTF_8));
        String jwt = token("id", UUID.randomUUID().toString(),
                Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS), otraKey);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat/text");
        request.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter("id").doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void headerAusenteDevuelve401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat/text");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter("id").doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void claimConfigurableSeLeeDelNombreCorrecto() throws Exception {
        UUID userId = UUID.randomUUID();
        String jwt = token("customClaim", userId.toString(),
                Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS), SIGNING_KEY);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat/text");
        request.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter("customClaim").doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        UserContext userContext = (UserContext) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(userContext.userId()).isEqualTo(userId);
    }

    @Test
    void claimSinUuidValidoDevuelve401YLogueaElClaimYElValor() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthenticationFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        String jwt = token("id", "correo@no-es-un-uuid.com",
                Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS), SIGNING_KEY);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat/text");
        request.addHeader("Authorization", "Bearer " + jwt);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        try {
            filter("id").doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(chain, never()).doFilter(request, response);
            assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("id")
                    && event.getFormattedMessage().contains("correo@no-es-un-uuid.com"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void rutasPublicasNoPasanPorElFiltro() {
        JwtAuthenticationFilter filter = filter("id");

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/v3/api-docs"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/swagger-ui/index.html"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/ai/chat/text"))).isFalse();
    }
}
