package com.myanimal.org.IA_service.application.dto;

import java.time.Instant;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentAnalysisResponse {

    private UUID documentId;
    private boolean supported;
    private String model;
    private Instant analyzedAt;
    private DocumentAnalysis analysis;
}
