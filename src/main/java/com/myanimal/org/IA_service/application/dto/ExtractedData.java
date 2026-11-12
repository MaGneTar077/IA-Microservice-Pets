package com.myanimal.org.IA_service.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExtractedData {

    private String nit;
    private String verificationDigit;
    private String legalName;
    private String legalRepresentativeName;
    private String issueDate;
    private String economicActivity;
}
