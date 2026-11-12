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
public class VerificationAnalyzeRequest {

    private String requestId;
    private String subjectType;
    private List<VerificationDocumentRequest> documents;
    private DeclaredData declared;
}
