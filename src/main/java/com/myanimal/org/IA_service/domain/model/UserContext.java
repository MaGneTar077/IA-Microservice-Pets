package com.myanimal.org.IA_service.domain.model;

import java.util.UUID;

import lombok.Builder;

/**
 * email/platformRole/ctx/vetId/vetRole/vetLicensed llegan del token (etapa 3.5, RS256) y hoy
 * nadie los lee todavía — se capturan porque el filtro ya se está tocando, antes de que
 * MyAnimaLogVet necesite vet_role/vet_licensed para decidir qué acciones ofrecer. En tokens
 * de usuario (ctx: "USER") los campos vet_* vienen null.
 */
@Builder
public record UserContext(UUID userId, String username, String rawJwt, String email, String platformRole,
        String ctx, UUID vetId, String vetRole, Boolean vetLicensed) {

    /** Constructor de conveniencia para los tests que no les importan los claims de clínica. */
    public UserContext(UUID userId, String username, String rawJwt) {
        this(userId, username, rawJwt, null, null, null, null, null, null);
    }
}
