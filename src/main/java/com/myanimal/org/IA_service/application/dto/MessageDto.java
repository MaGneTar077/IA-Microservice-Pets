package com.myanimal.org.IA_service.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MessageDto {

    private UUID id;
    private String rol;
    private String contenido;
    private List<AttachmentDto> adjuntos;
    private Instant createdAt;
}
