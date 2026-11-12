package com.myanimal.org.IA_service.application.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.DocumentExtraction;
import com.myanimal.org.IA_service.application.dto.DocumentExtractionResult;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.model.AiMessage;
import com.myanimal.org.IA_service.domain.model.AiPart;
import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.model.AiRole;
import com.myanimal.org.IA_service.domain.model.DocumentType;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.DocumentExtractionSchema;

import lombok.extern.slf4j.Slf4j;

/**
 * Extracción con salida estructurada: el documento viaja como dato, nunca como instrucción,
 * y la validación de esquema permite un único reintento antes de rendirse con
 * AnalysisUnavailableException (503 AI_PROVIDER_UNAVAILABLE). Usa el AiModelPort dedicado a
 * verificación (geminiVerificationAdapter): otro timeout, sin fallback de modelo.
 */
@Slf4j
@Component
class GeminiDocumentExtractor {

    private final AiModelPort aiModelPort;
    private final String promptText;
    private final ObjectMapper objectMapper;

    GeminiDocumentExtractor(@Qualifier("geminiVerificationAdapter") AiModelPort aiModelPort,
            @Qualifier("documentAnalysisPromptText") String promptText, ObjectMapper objectMapper) {
        this.aiModelPort = aiModelPort;
        this.promptText = promptText;
        this.objectMapper = objectMapper;
    }

    DocumentExtractionResult extract(DocumentType documentType, DeclaredData declared, byte[] content,
            String mimeType) {
        AiRequest aiRequest = buildRequest(documentType, declared, content, mimeType);

        AiResponse response = aiModelPort.generate(aiRequest);
        Optional<DocumentExtraction> extraction = tryParse(response.getText());
        if (extraction.isPresent()) {
            return toResult(extraction.get(), response.getModelUsed());
        }

        log.warn("La respuesta de Gemini no cumplió el esquema de extracción esperado; reintentando una vez.");
        AiResponse retryResponse = aiModelPort.generate(aiRequest);
        return tryParse(retryResponse.getText())
                .map(result -> toResult(result, retryResponse.getModelUsed()))
                .orElseThrow(() -> new AnalysisUnavailableException(
                        "La extracción no cumplió el formato esperado tras reintentar."));
    }

    private DocumentExtractionResult toResult(DocumentExtraction extraction, String modelUsed) {
        return DocumentExtractionResult.builder().extraction(extraction).modelUsed(modelUsed).build();
    }

    private Optional<DocumentExtraction> tryParse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(text, DocumentExtraction.class));
        } catch (JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    private AiRequest buildRequest(DocumentType documentType, DeclaredData declared, byte[] content,
            String mimeType) {
        AiMessage userMessage = AiMessage.builder()
                .role(AiRole.USER)
                .parts(List.of(AiPart.ofText(declaredDataText(documentType, declared)), AiPart.ofBinary(mimeType, content)))
                .build();

        return AiRequest.builder()
                .systemInstruction(promptText)
                .messages(List.of(userMessage))
                .responseSchema(DocumentExtractionSchema.schema())
                .build();
    }

    private String declaredDataText(DocumentType documentType, DeclaredData declared) {
        StringBuilder text = new StringBuilder();
        text.append("Tipo de documento declarado: ").append(documentType.name()).append('\n');

        if (documentType == DocumentType.LEGAL_REP_ID) {
            // Privacidad: nunca se pide el número de documento, ni siquiera para descartarlo.
            text.append("Este documento es la cédula/identificación del representante legal. ")
                    .append("Extrae ÚNICAMENTE el nombre completo de la persona en el campo ")
                    .append("legalRepresentativeName. No extraigas, repitas, resumas ni menciones en NINGÚN ")
                    .append("campo (incluido summary) el número de documento/cédula/identificación, aunque ")
                    .append("aparezca claramente visible en la imagen.\n");
            text.append("Representante legal declarado: ")
                    .append(orEmpty(declared == null ? null : declared.getLegalRepresentativeName()));
            return text.toString();
        }

        if (declared == null) {
            text.append("La clínica todavía no declaró sus datos.");
            return text.toString();
        }

        text.append("Datos declarados por la clínica:\n")
                .append("- Razón social: ").append(orEmpty(declared.getLegalName())).append('\n')
                .append("- NIT: ").append(orEmpty(declared.getNit())).append('\n')
                .append("- Representante legal: ").append(orEmpty(declared.getLegalRepresentativeName())).append('\n')
                .append("- Ciudad: ").append(orEmpty(declared.getCity()));
        return text.toString();
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
