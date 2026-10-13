package com.myanimal.org.IA_service.infrastructure.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.myanimal.org.IA_service.infrastructure.security.JwtProperties;

/**
 * El JwtDecoder valida contra el JWKS de user-service (RS256): firma con la llave cuyo `kid`
 * traiga el header, caché de llaves con refresco automático si llega un `kid` desconocido
 * (comportamiento de NimbusJwtDecoder/RemoteJWKSet, no escrito a mano), `iss`, `aud` y `exp`.
 * No hay camino legacy HS256: los tokens viejos simplemente vencen (máximo 1 hora) y el
 * usuario pide uno nuevo.
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwksUri()).build();
        decoder.setJwtValidator(buildValidator(properties.getIssuer(), properties.getAudience()));
        return decoder;
    }

    /**
     * Expuesto para que JwtAuthenticationFilterTest valide con el mismo iss/aud que
     * producción, en vez de reimplementar la combinación de validadores en el test.
     */
    public static OAuth2TokenValidator<Jwt> buildValidator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> withAudience = new JwtClaimValidator<List<String>>(
                "aud", aud -> aud != null && aud.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience);
    }
}
