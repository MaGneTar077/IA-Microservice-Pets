package com.myanimal.org.IA_service.infrastructure.adapters.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingActionJpaRepository extends JpaRepository<PendingActionEntity, UUID> {

    Optional<PendingActionEntity> findByIdAndUserId(UUID id, UUID userId);
}
