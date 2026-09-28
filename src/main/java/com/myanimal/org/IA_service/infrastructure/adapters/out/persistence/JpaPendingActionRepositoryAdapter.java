package com.myanimal.org.IA_service.infrastructure.adapters.out.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.domain.model.PendingAction;
import com.myanimal.org.IA_service.domain.model.PendingActionStatus;
import com.myanimal.org.IA_service.domain.ports.out.PendingActionRepositoryPort;

// "test" usa NoopPendingActionRepositoryPort (src/test): el perfil test no monta datasource/JPA.
@Profile("!test")
@Component
public class JpaPendingActionRepositoryAdapter implements PendingActionRepositoryPort {

    private final PendingActionJpaRepository repository;

    public JpaPendingActionRepositoryAdapter(PendingActionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public PendingAction create(UUID conversationId, UUID userId, String toolName, Map<String, Object> toolArgs) {
        PendingActionEntity entity = PendingActionEntity.builder()
                .id(UUID.randomUUID())
                .conversationId(conversationId)
                .userId(userId)
                .toolName(toolName)
                .toolArgs(toolArgs)
                .estado(PendingActionStatus.PENDIENTE)
                .build();
        return toDomain(repository.save(entity));
    }

    @Override
    public Optional<PendingAction> findByIdAndUser(UUID id, UUID userId) {
        return repository.findByIdAndUserId(id, userId).map(this::toDomain);
    }

    @Override
    public PendingAction updateStatus(UUID id, PendingActionStatus status, Instant resolvedAt) {
        PendingActionEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Pending action no encontrada: " + id));
        entity.setEstado(status);
        entity.setResolvedAt(resolvedAt);
        return toDomain(repository.save(entity));
    }

    private PendingAction toDomain(PendingActionEntity entity) {
        return PendingAction.builder()
                .id(entity.getId())
                .conversationId(entity.getConversationId())
                .userId(entity.getUserId())
                .toolName(entity.getToolName())
                .toolArgs(entity.getToolArgs())
                .estado(entity.getEstado())
                .createdAt(entity.getCreatedAt())
                .resolvedAt(entity.getResolvedAt())
                .expiresAt(entity.getExpiresAt())
                .build();
    }
}
