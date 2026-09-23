package com.myanimal.org.IA_service.infrastructure.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
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
@Table(name = "ai_message")
public class MessageEntity {

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Convert(converter = MessageRoleConverter.class)
    @Column(name = "rol", nullable = false)
    private com.myanimal.org.IA_service.domain.model.MessageRole role;

    @Column(name = "contenido")
    private String contenido;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "adjuntos", nullable = false)
    @Builder.Default
    private List<AttachmentJson> adjuntos = List.of();

    // Solo para rol='tool'; no se usan en esta etapa (los llena la etapa 3).
    @Column(name = "tool_name")
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_args")
    private String toolArgs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_result")
    private String toolResult;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
