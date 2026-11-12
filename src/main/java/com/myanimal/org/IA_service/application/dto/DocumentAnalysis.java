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
public class DocumentAnalysis {

    private String detectedType;
    private Boolean typeMatches;
    private Boolean legible;
    private ExtractedData extracted;
    private List<DocumentCheck> checks;
    private List<String> flags;
    private Double confidence;
    private String summary;
    private String recommendation;
}
