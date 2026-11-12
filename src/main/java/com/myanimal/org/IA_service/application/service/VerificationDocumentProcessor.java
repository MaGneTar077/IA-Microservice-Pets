package com.myanimal.org.IA_service.application.service;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysis;
import com.myanimal.org.IA_service.application.dto.DocumentExtractionResult;
import com.myanimal.org.IA_service.application.dto.VerificationDocumentResult;
import com.myanimal.org.IA_service.application.service.DocumentAnalysisCache.CachedAnalysis;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisValidationException;
import com.myanimal.org.IA_service.domain.exception.FileTooLargeException;
import com.myanimal.org.IA_service.domain.exception.FileUrlNotAllowedException;
import com.myanimal.org.IA_service.domain.exception.UnsupportedFileTypeException;
import com.myanimal.org.IA_service.domain.model.DocumentType;
import com.myanimal.org.IA_service.domain.model.DownloadedFile;
import com.myanimal.org.IA_service.domain.ports.out.RemoteFileDownloadPort;
import com.myanimal.org.IA_service.infrastructure.adapters.in.attachment.MagicByteMimeDetector;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Pipeline de un documento dentro de /internal/verification/analyze: valida el host de la
 * URL firmada, descarga, detecta el tipo de archivo (Veterinary no manda mimeType),
 * calcula el sha256, revisa la caché, extrae con Gemini y corre los checks. Cualquier fallo
 * se captura aquí y se convierte en {@code error} en el resultado del documento — nunca
 * tumba el procesamiento de los demás documentos de la solicitud.
 */
@Slf4j
@Component
class VerificationDocumentProcessor {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");

    private final RemoteFileDownloadPort remoteFileDownloadPort;
    private final DocumentAnalysisProperties documentAnalysisProperties;
    private final GeminiDocumentExtractor geminiDocumentExtractor;
    private final DocumentCheckEngine documentCheckEngine;
    private final DocumentAnalysisCache documentAnalysisCache;

    VerificationDocumentProcessor(RemoteFileDownloadPort remoteFileDownloadPort,
            DocumentAnalysisProperties documentAnalysisProperties, GeminiDocumentExtractor geminiDocumentExtractor,
            DocumentCheckEngine documentCheckEngine, DocumentAnalysisCache documentAnalysisCache) {
        this.remoteFileDownloadPort = remoteFileDownloadPort;
        this.documentAnalysisProperties = documentAnalysisProperties;
        this.geminiDocumentExtractor = geminiDocumentExtractor;
        this.documentCheckEngine = documentCheckEngine;
        this.documentAnalysisCache = documentAnalysisCache;
    }

    VerificationDocumentResult process(DocumentType documentType, String signedUrl, DeclaredData declared) {
        if (!documentType.isSupported()) {
            return VerificationDocumentResult.builder().documentType(documentType.name()).supported(false).build();
        }

        try {
            DocumentAnalysis analysis = analyze(documentType, signedUrl, declared);
            return VerificationDocumentResult.builder()
                    .documentType(documentType.name())
                    .supported(true)
                    .analysis(analysis)
                    .build();
        } catch (RuntimeException ex) {
            log.warn("Fallo analizando un documento de verificación: tipo={} causa={}",
                    documentType, ex.getClass().getSimpleName());
            return VerificationDocumentResult.builder()
                    .documentType(documentType.name())
                    .supported(true)
                    .error(errorMessage(ex))
                    .build();
        }
    }

    private DocumentAnalysis analyze(DocumentType documentType, String signedUrl, DeclaredData declared) {
        validateFileUrlAllowed(signedUrl);
        DownloadedFile file = remoteFileDownloadPort.download(signedUrl);
        String mimeType = detectMimeType(file);
        String sha256 = sha256Hex(file.content());
        String cacheKey = sha256 + ":" + documentType.name();

        Optional<CachedAnalysis> cached = documentAnalysisCache.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get().analysis();
        }

        if ("application/pdf".equals(mimeType)) {
            PdfPageCounter.countPages(file.content()).ifPresent(pageCount -> {
                if (pageCount > documentAnalysisProperties.getMaxPages()) {
                    throw new FileTooLargeException(
                            "El documento supera el máximo de " + documentAnalysisProperties.getMaxPages()
                                    + " páginas.");
                }
            });
        }

        DocumentExtractionResult extractionResult =
                geminiDocumentExtractor.extract(documentType, declared, file.content(), mimeType);
        DocumentAnalysis analysis = documentCheckEngine.buildAnalysis(documentType, declared,
                extractionResult.getExtraction());
        documentAnalysisCache.put(cacheKey, new CachedAnalysis(extractionResult.getModelUsed(), analysis));
        return analysis;
    }

    private String detectMimeType(DownloadedFile file) {
        Optional<String> detected = MagicByteMimeDetector.detect(file.content());
        if (detected.isPresent()) {
            if (!ALLOWED_MIME_TYPES.contains(detected.get())) {
                throw new UnsupportedFileTypeException(
                        "El tipo de archivo " + detected.get() + " no está permitido.");
            }
            return detected.get();
        }

        String contentType = file.contentType();
        if (contentType != null) {
            String base = contentType.split(";")[0].trim().toLowerCase();
            if (ALLOWED_MIME_TYPES.contains(base)) {
                return base;
            }
        }
        throw new UnsupportedFileTypeException("No se pudo determinar un tipo de archivo permitido.");
    }

    private void validateFileUrlAllowed(String signedUrl) {
        URI uri;
        try {
            uri = URI.create(signedUrl);
        } catch (IllegalArgumentException ex) {
            throw new FileUrlNotAllowedException("signedUrl no es una URL válida.");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !documentAnalysisProperties.getAllowedFileHosts().contains(uri.getHost())) {
            throw new FileUrlNotAllowedException("El host de signedUrl no está permitido.");
        }
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM.", ex);
        }
    }

    private String errorMessage(RuntimeException ex) {
        if (ex instanceof FileUrlNotAllowedException || ex instanceof UnsupportedFileTypeException
                || ex instanceof FileTooLargeException || ex instanceof AnalysisUnavailableException
                || ex instanceof DocumentAnalysisValidationException) {
            return ex.getMessage();
        }
        return "Error inesperado procesando el documento.";
    }
}
