package com.myanimal.org.IA_service.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class PendingAction {

    private UUID id;
    private UUID conversationId;
    private UUID userId;
    private String toolName;
    private Map<String, Object> toolArgs;
    private PendingActionStatus estado;
    private Instant createdAt;
    private Instant resolvedAt;
    private Instant expiresAt;
}
