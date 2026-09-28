package com.myanimal.org.IA_service.application.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PendingActionDto {

    private UUID id;
    private String tool;
    private String descripcion;
    private Map<String, Object> args;
    private Instant expiresAt;
}
