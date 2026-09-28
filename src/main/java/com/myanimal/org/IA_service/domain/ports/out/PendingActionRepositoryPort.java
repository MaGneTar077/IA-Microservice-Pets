package com.myanimal.org.IA_service.domain.ports.out;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.myanimal.org.IA_service.domain.model.PendingAction;
import com.myanimal.org.IA_service.domain.model.PendingActionStatus;

public interface PendingActionRepositoryPort {

    PendingAction create(UUID conversationId, UUID userId, String toolName, Map<String, Object> toolArgs);

    Optional<PendingAction> findByIdAndUser(UUID id, UUID userId);

    PendingAction updateStatus(UUID id, PendingActionStatus status, Instant resolvedAt);
}
