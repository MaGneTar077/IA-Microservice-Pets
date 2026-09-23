package com.myanimal.org.IA_service.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class Message {

    private UUID id;
    private UUID conversationId;
    private MessageRole role;
    private String contenido;
    private List<MessageAttachment> adjuntos;
    private String toolName;
    private String toolArgs;
    private String toolResult;
    private Instant createdAt;
}
