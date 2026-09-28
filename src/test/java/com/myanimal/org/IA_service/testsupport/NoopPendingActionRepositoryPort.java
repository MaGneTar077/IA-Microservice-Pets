package com.myanimal.org.IA_service.testsupport;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.domain.model.PendingAction;
import com.myanimal.org.IA_service.domain.model.PendingActionStatus;
import com.myanimal.org.IA_service.domain.ports.out.PendingActionRepositoryPort;

/**
 * Sustituye a JpaPendingActionRepositoryAdapter en el perfil "test" (sin datasource/JPA).
 * Solo existe para que IaServiceApplicationTests arranque el contexto completo offline.
 */
@Component
@Profile("test")
public class NoopPendingActionRepositoryPort implements PendingActionRepositoryPort {

    @Override
    public PendingAction create(UUID conversationId, UUID userId, String toolName, Map<String, Object> toolArgs) {
        throw new UnsupportedOperationException("Fake de test: no persiste pending actions.");
    }

    @Override
    public Optional<PendingAction> findByIdAndUser(UUID id, UUID userId) {
        return Optional.empty();
    }

    @Override
    public PendingAction updateStatus(UUID id, PendingActionStatus status, Instant resolvedAt) {
        throw new UnsupportedOperationException("Fake de test: no persiste pending actions.");
    }
}
