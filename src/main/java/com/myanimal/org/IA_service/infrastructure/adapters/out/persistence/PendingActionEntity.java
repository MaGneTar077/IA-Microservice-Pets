package com.myanimal.org.IA_service.infrastructure.adapters.out.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import com.myanimal.org.IA_service.domain.model.PendingActionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "ai_pending_action")
public class PendingActionEntity {

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_args", nullable = false)
    private Map<String, Object> toolArgs;

    // El check de la tabla ya usa los mismos literales que el enum (PENDIENTE, CONFIRMADA,
    // RECHAZADA, EXPIRADA) — a diferencia de MessageRole no hace falta un converter.
    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private PendingActionStatus estado;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "expires_at", insertable = false, updatable = false)
    private Instant expiresAt;
}
