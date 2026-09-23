package com.myanimal.org.IA_service.domain.model;

import java.time.Instant;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class Conversation {

    private UUID id;
    private UUID userId;
    private String titulo;
    private Instant createdAt;
    private Instant updatedAt;
}
