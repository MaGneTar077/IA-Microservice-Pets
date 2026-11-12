package com.myanimal.org.IA_service.application.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VerificationAnalyzeResponse {

    private String requestId;
    private String recommendation;
    private String riskLevel;
    private List<VerificationDocumentResult> documents;
}
