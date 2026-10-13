package com.myanimal.org.IA_service.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.infrastructure.config.JwtDecoderConfig;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.slf4j.LoggerFactory;

/**
 * Etapa 3.5: el filtro ya no verifica con un secreto compartido, valida contra un JWKS
 * (RS256). La mayoría de los tests usan NimbusJwtDecoder.withPublicKey(...) directo — sin
 * red — con el mismo validador de iss/aud que arma JwtDecoderConfig en producción. Solo el
 * test de rotación de kid necesita un JWKS servido de verdad, vía un HttpServer del JDK
 * efímero (cero dependencias nuevas).
 */
class JwtAuthenticationFilterTest {

    private static final String ISSUER = "myanimalog-user-service";
    private static final String AUDIENCE = "myanimalog-api";

    private final KeyPair keyPair = generateRsaKeyPair();

    private HttpServer jwksServer;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (jwksServer != null) {
            jwksServer.stop(0);
            jwksServer = null;
        }
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JwtDecoder decoderFor(RSAPublicKey publicKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(JwtDecoderConfig.buildValidator(ISSUER, AUDIENCE));
        return decoder;
    }

    private JwtAuthenticationFilter filter(JwtDecoder decoder) {
        return new JwtAuthenticationFilter(decoder, new ObjectMapper());
    }

    private String token(RSAPrivateKey signingKey, String issuer, String audience, Instant issuedAt,
            Instant expiration, Map<String, Object> claims) {
        return Jwts.builder()
                .claims(claims)
                .claim("iss", issuer)
                .claim("aud", List.of(audience))
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiration))
                .signWith(signingKey, Jwts.SIG.RS256)
                .compact();
    }

    private String validToken(RSAPrivateKey signingKey, Map<String, Object> claims) {
        return token(signingKey, ISSUER, AUDIENCE, Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS), claims);
    }

    private MockHttpServletResponse authenticate(JwtAuthenticationFilter filter, String bearerToken)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat/text");
        if (bearerToken != null) {
            request.addHeader("Authorization", "Bearer " + bearerToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, response.getStatus() == 200 ? times(1) : never()).doFilter(request, response);
        return response;
    }

    @Test
    void tokenRs256ValidoAutenticaYElUserContextTraeElUuidDelClaimId() throws Exception {
        UUID userId = UUID.randomUUID();
        String jwt = validToken((RSAPrivateKey) keyPair.getPrivate(), Map.of("id", userId.toString()));

        MockHttpServletResponse response = authenticate(filter(decoderFor((RSAPublicKey) keyPair.getPublic())), jwt);

        assertThat(response.getStatus()).isEqualTo(200);
        UserContext userContext = (UserContext) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(userContext.userId()).isEqualTo(userId);
        assertThat(userContext.rawJwt()).isEqualTo(jwt);
    }

    @Test
    void issIncorrectoDevuelve401() throws Exception {
        String jwt = token((RSAPrivateKey) keyPair.getPrivate(), "otro-issuer", AUDIENCE,
                Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS), Map.of("id", UUID.randomUUID().toString()));

        MockHttpServletResponse response = authenticate(filter(decoderFor((RSAPublicKey) keyPair.getPublic())), jwt);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void audIncorrectoDevuelve401() throws Exception {
        String jwt = token((RSAPrivateKey) keyPair.getPrivate(), ISSUER, "otra-audiencia",
                Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS), Map.of("id", UUID.randomUUID().toString()));

        MockHttpServletResponse response = authenticate(filter(decoderFor((RSAPublicKey) keyPair.getPublic())), jwt);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void tokenExpiradoDevuelve401() throws Exception {
        String jwt = token((RSAPrivateKey) keyPair.getPrivate(), ISSUER, AUDIENCE,
                Instant.now().minus(2, ChronoUnit.HOURS), Instant.now().minus(1, ChronoUnit.HOURS),
                Map.of("id", UUID.randomUUID().toString()));

        MockHttpServletResponse response = authenticate(filter(decoderFor((RSAPublicKey) keyPair.getPublic())), jwt);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void firmaInvalidaDevuelve401() throws Exception {
        KeyPair otroPar = generateRsaKeyPair();
        String jwt = validToken((RSAPrivateKey) otroPar.getPrivate(), Map.of("id", UUID.randomUUID().toString()));

        // Se decodifica con la llave pública del par "oficial", no con la que firmó el token.
        MockHttpServletResponse response = authenticate(filter(decoderFor((RSAPublicKey) keyPair.getPublic())), jwt);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void tokenHs256LegacyDevuelve401() throws Exception {
        SecretKey legacyKey = Keys.hmacShaKeyFor("0123456789abcdef0123456789abcdef".getBytes());
        String jwt = Jwts.builder()
                .claim("id", UUID.randomUUID().toString())
                .claim("iss", ISSUER)
                .claim("aud", List.of(AUDIENCE))
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(legacyKey)
                .compact();

        MockHttpServletResponse response = authenticate(filter(decoderFor((RSAPublicKey) keyPair.getPublic())), jwt);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void headerAusenteDevuelve401() throws Exception {
        MockHttpServletResponse response = authenticate(filter(decoderFor((RSAPublicKey) keyPair.getPublic())), null);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void claimIdSinUuidValidoDevuelve401YLogueaElClaimYElValorSinElToken() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthenticationFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        String jwt = validToken((RSAPrivateKey) keyPair.getPrivate(), Map.of("id", "correo@no-es-un-uuid.com"));

        try {
            MockHttpServletResponse response = authenticate(
                    filter(decoderFor((RSAPublicKey) keyPair.getPublic())), jwt);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("id")
                    && event.getFormattedMessage().contains("correo@no-es-un-uuid.com"));
            assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains(jwt));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void tokenDeClinicaAutenticaYLosCamposVetQuedanPoblados() throws Exception {
        UUID vetId = UUID.randomUUID();
        String jwt = validToken((RSAPrivateKey) keyPair.getPrivate(), Map.of(
                "id", UUID.randomUUID().toString(),
                "ctx", "VETERINARY",
                "vet_id", vetId.toString(),
                "vet_role", "VETERINARIAN",
                "vet_licensed", true));

        authenticate(filter(decoderFor((RSAPublicKey) keyPair.getPublic())), jwt);

        UserContext userContext = (UserContext) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(userContext.ctx()).isEqualTo("VETERINARY");
        assertThat(userContext.vetId()).isEqualTo(vetId);
        assertThat(userContext.vetRole()).isEqualTo("VETERINARIAN");
        assertThat(userContext.vetLicensed()).isTrue();
    }

    @Test
    void tokenDeUsuarioLosCamposVetQuedanNulos() throws Exception {
        String jwt = validToken((RSAPrivateKey) keyPair.getPrivate(), Map.of(
                "id", UUID.randomUUID().toString(),
                "ctx", "USER",
                "email", "ana@correo.com",
                "platform_role", "USER"));

        authenticate(filter(decoderFor((RSAPublicKey) keyPair.getPublic())), jwt);

        UserContext userContext = (UserContext) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(userContext.ctx()).isEqualTo("USER");
        assertThat(userContext.email()).isEqualTo("ana@correo.com");
        assertThat(userContext.platformRole()).isEqualTo("USER");
        assertThat(userContext.vetId()).isNull();
        assertThat(userContext.vetRole()).isNull();
        assertThat(userContext.vetLicensed()).isNull();
    }

    @Test
    void rutasPublicasNoPasanPorElFiltro() {
        JwtAuthenticationFilter filter = filter(decoderFor((RSAPublicKey) keyPair.getPublic()));

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/v3/api-docs"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/swagger-ui/index.html"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/ai/chat/text"))).isFalse();
    }

    // ---- Rotación de kid: necesita un JWKS servido de verdad, no withPublicKey() ----

    private String jwksJson(RSAPublicKey... publicKeysWithMatchingKid) throws Exception {
        List<com.nimbusds.jose.jwk.JWK> jwks = new java.util.ArrayList<>();
        for (int i = 0; i < publicKeysWithMatchingKid.length; i++) {
            jwks.add(new RSAKey.Builder(publicKeysWithMatchingKid[i])
                    .keyID("key-" + i)
                    .algorithm(JWSAlgorithm.RS256)
                    .build());
        }
        return new JWKSet(jwks).toString();
    }

    private HttpServer startJwksServer(AtomicReference<String> jwksBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            byte[] body = jwksBody.get().getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void kidDesconocidoRefrescaElJwksYAutenticaConLaLlaveNueva() throws Exception {
        KeyPair keyUno = generateRsaKeyPair();
        KeyPair keyDos = generateRsaKeyPair();

        AtomicReference<String> jwksBody = new AtomicReference<>(
                jwksJson((RSAPublicKey) keyUno.getPublic()));
        jwksServer = startJwksServer(jwksBody);
        String jwksUri = "http://localhost:" + jwksServer.getAddress().getPort() + "/.well-known/jwks.json";

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(JwtDecoderConfig.buildValidator(ISSUER, AUDIENCE));
        JwtAuthenticationFilter filter = filter(decoder);

        // key-0 ya está en el JWKS servido: autentica sin problema.
        String jwtConKeyUno = tokenConKid((RSAPrivateKey) keyUno.getPrivate(), "key-0",
                Map.of("id", UUID.randomUUID().toString()));
        assertThat(authenticate(filter, jwtConKeyUno).getStatus()).isEqualTo(200);

        // user-service rota: ahora publica key-0 y key-1. Un token firmado con key-1 (kid
        // "key-1") no está en la caché todavía — RemoteJWKSet debe refrescar y encontrarla.
        jwksBody.set(jwksJson((RSAPublicKey) keyUno.getPublic(), (RSAPublicKey) keyDos.getPublic()));
        String jwtConKeyDos = tokenConKid((RSAPrivateKey) keyDos.getPrivate(), "key-1",
                Map.of("id", UUID.randomUUID().toString()));

        assertThat(authenticate(filter, jwtConKeyDos).getStatus()).isEqualTo(200);
    }

    @Test
    void kidQueNuncaApareceEnElJwksDevuelve401() throws Exception {
        KeyPair keyUno = generateRsaKeyPair();
        KeyPair keyQueNuncaSePublica = generateRsaKeyPair();

        AtomicReference<String> jwksBody = new AtomicReference<>(jwksJson((RSAPublicKey) keyUno.getPublic()));
        jwksServer = startJwksServer(jwksBody);
        String jwksUri = "http://localhost:" + jwksServer.getAddress().getPort() + "/.well-known/jwks.json";

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(JwtDecoderConfig.buildValidator(ISSUER, AUDIENCE));
        JwtAuthenticationFilter filter = filter(decoder);

        String jwt = tokenConKid((RSAPrivateKey) keyQueNuncaSePublica.getPrivate(), "key-fantasma",
                Map.of("id", UUID.randomUUID().toString()));

        assertThat(authenticate(filter, jwt).getStatus()).isEqualTo(401);
    }

    private String tokenConKid(RSAPrivateKey signingKey, String kid, Map<String, Object> claims) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .claims(claims)
                .claim("iss", ISSUER)
                .claim("aud", List.of(AUDIENCE))
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(signingKey, Jwts.SIG.RS256)
                .compact();
    }
}
