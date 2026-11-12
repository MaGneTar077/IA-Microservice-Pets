package com.myanimal.org.IA_service.application.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lo que Gemini extrae del documento (salida estructurada). typeMatches, checks y
 * recommendation NO están aquí: esos se calculan en código a partir de esto, nunca el LLM.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentExtraction {

    private String detectedType;
    private Boolean legible;
    private ExtractedData extracted;
    private List<String> flags;
    private Double confidence;
    private String summary;
}
