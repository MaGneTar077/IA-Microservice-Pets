package com.myanimal.org.IA_service.infrastructure.adapters.out.gemini;

import java.util.List;
import java.util.Map;

/**
 * Esquema de salida estructurada para la extracción de documentos de verificación
 * (responseSchema de Gemini, subconjunto de OpenAPI Schema con tipos en mayúsculas).
 * Solo campos que el modelo puede observar del documento: nada de typeMatches, checks
 * ni recommendation, que se calculan en código.
 */
public final class DocumentExtractionSchema {

    private static final List<String> FLAG_VALUES = List.of(
            "LOW_QUALITY", "WRONG_DOCUMENT", "PAGES_MISSING", "POSSIBLE_TAMPERING",
            "INSTRUCTIONS_IN_DOCUMENT");

    private DocumentExtractionSchema() {
    }

    public static Map<String, Object> schema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "detectedType", Map.of(
                                "type", "STRING",
                                "enum", List.of("RUT", "CAMARA_COMERCIO", "OTHER")),
                        "legible", Map.of("type", "BOOLEAN"),
                        "extracted", extractedSchema(),
                        "flags", Map.of(
                                "type", "ARRAY",
                                "items", Map.of("type", "STRING", "enum", FLAG_VALUES)),
                        "confidence", Map.of("type", "NUMBER"),
                        "summary", Map.of("type", "STRING")),
                "required", List.of("detectedType", "legible", "extracted", "flags", "confidence", "summary"));
    }

    private static Map<String, Object> extractedSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "nit", Map.of("type", "STRING", "nullable", true),
                        "verificationDigit", Map.of("type", "STRING", "nullable", true),
                        "legalName", Map.of("type", "STRING", "nullable", true),
                        "legalRepresentativeName", Map.of("type", "STRING", "nullable", true),
                        "issueDate", Map.of("type", "STRING", "nullable", true),
                        "economicActivity", Map.of("type", "STRING", "nullable", true)));
    }
}
