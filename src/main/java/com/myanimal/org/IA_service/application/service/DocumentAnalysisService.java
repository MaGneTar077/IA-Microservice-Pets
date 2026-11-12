package com.myanimal.org.IA_service.application.service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.myanimal.org.IA_service.application.dto.DocumentAnalysisRequest;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysisResponse;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisValidationException;
import com.myanimal.org.IA_service.domain.exception.FileTooLargeException;
import com.myanimal.org.IA_service.domain.exception.FileUrlNotAllowedException;
import com.myanimal.org.IA_service.domain.exception.UnsupportedFileTypeException;
import com.myanimal.org.IA_service.domain.model.DocumentType;
import com.myanimal.org.IA_service.domain.ports.in.DocumentAnalysisUseCase;
import com.myanimal.org.IA_service.domain.ports.out.RemoteFileDownloadPort;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisProperties;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DocumentAnalysisService implements DocumentAnalysisUseCase {

    private static final Set<String> ALLOWED_MIME_TYPES =
            Set.of("application/pdf", "image/jpeg", "image/png");

    private final Clock clock;
    private final RemoteFileDownloadPort remoteFileDownloadPort;
    private final DocumentAnalysisProperties documentAnalysisProperties;

    @Override
    public DocumentAnalysisResponse analyze(DocumentAnalysisRequest request) {
        validate(request);

        Optional<DocumentType> type = DocumentType.fromCode(request.getDocumentType());
        if (type.isEmpty()) {
            return DocumentAnalysisResponse.builder()
                    .documentId(request.getDocumentId())
                    .supported(false)
                    .analyzedAt(Instant.now(clock))
                    .build();
        }

        byte[] content = remoteFileDownloadPort.download(request.getFileUrl());
        if ("application/pdf".equals(request.getMimeType())) {
            PdfPageCounter.countPages(content).ifPresent(pageCount -> {
                if (pageCount > documentAnalysisProperties.getMaxPages()) {
                    throw new FileTooLargeException(
                            "El documento supera el máximo de " + documentAnalysisProperties.getMaxPages()
                                    + " páginas.");
                }
            });
        }

        // Bloques 3-4 reemplazan esto por la extracción con Gemini y los checks en código.
        throw new AnalysisUnavailableException(
                "El análisis de documentos todavía no está implementado para " + type.get().name() + ".");
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

        if (!ALLOWED_MIME_TYPES.contains(request.getMimeType())) {
            throw new UnsupportedFileTypeException(
                    "El tipo de archivo " + request.getMimeType() + " no está permitido.");
        }
        validateFileUrlAllowed(request.getFileUrl());
    }

    private void validateFileUrlAllowed(String fileUrl) {
        URI uri;
        try {
            uri = URI.create(fileUrl);
        } catch (IllegalArgumentException ex) {
            throw new FileUrlNotAllowedException("fileUrl no es una URL válida.");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !documentAnalysisProperties.getAllowedFileHosts().contains(uri.getHost())) {
            throw new FileUrlNotAllowedException("El host de fileUrl no está permitido.");
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
