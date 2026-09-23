package com.myanimal.org.IA_service.domain.model;

import java.util.UUID;

public record UserContext(UUID userId, String rawJwt) {
}
