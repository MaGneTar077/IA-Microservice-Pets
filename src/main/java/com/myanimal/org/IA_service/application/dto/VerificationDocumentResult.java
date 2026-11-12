package com.myanimal.org.IA_service.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VerificationDocumentResult {

    private String documentType;
    private boolean supported;
    private DocumentAnalysis analysis;
    private String error;
}
