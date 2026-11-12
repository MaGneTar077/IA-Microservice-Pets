package com.myanimal.org.IA_service.application.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import com.myanimal.org.IA_service.application.dto.DocumentAnalysisRequest;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysisResponse;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisValidationException;
import com.myanimal.org.IA_service.domain.model.DocumentType;
import com.myanimal.org.IA_service.domain.ports.in.DocumentAnalysisUseCase;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DocumentAnalysisService implements DocumentAnalysisUseCase {

    private final Clock clock;

    @Override
    public DocumentAnalysisResponse analyze(DocumentAnalysisRequest request) {
        validate(request);

        return DocumentType.fromCode(request.getDocumentType())
                .<DocumentAnalysisResponse>map(type -> {
                    // Bloques 2-4 reemplazan esto por la descarga, extracción con Gemini y checks en código.
                    throw new AnalysisUnavailableException(
                            "El análisis de documentos todavía no está implementado para " + type.name() + ".");
                })
                .orElseGet(() -> DocumentAnalysisResponse.builder()
                        .documentId(request.getDocumentId())
                        .supported(false)
                        .analyzedAt(Instant.now(clock))
                        .build());
    }

    private void validate(DocumentAnalysisRequest request) {
        requireNotNull(request.getDocumentId(), "documentId es obligatorio.");
        requireNotNull(request.getVeterinaryId(), "veterinaryId es obligatorio.");
        requireNotBlank(request.getDocumentType(), "documentType es obligatorio.");
        requireNotBlank(request.getFileUrl(), "fileUrl es obligatorio.");
        requireNotBlank(request.getMimeType(), "mimeType es obligatorio.");
        requireNotBlank(request.getSha256(), "sha256 es obligatorio.");
        if (request.getDeclared() == null) {
            throw new DocumentAnalysisValidationException("declared es obligatorio.");
        }
    }

    private void requireNotNull(Object value, String message) {
        if (value == null) {
            throw new DocumentAnalysisValidationException(message);
        }
    }

    private void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new DocumentAnalysisValidationException(message);
        }
    }
}
