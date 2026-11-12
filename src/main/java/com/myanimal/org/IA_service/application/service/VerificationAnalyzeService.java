package com.myanimal.org.IA_service.application.service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.VerificationAnalyzeRequest;
import com.myanimal.org.IA_service.application.dto.VerificationAnalyzeResponse;
import com.myanimal.org.IA_service.application.dto.VerificationDocumentRequest;
import com.myanimal.org.IA_service.application.dto.VerificationDocumentResult;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisValidationException;
import com.myanimal.org.IA_service.domain.model.DocumentType;
import com.myanimal.org.IA_service.domain.ports.in.VerificationAnalyzeUseCase;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisProperties;

/**
 * Orquesta /internal/verification/analyze: valida la forma de la solicitud, aplica el rate
 * limit, procesa los documentos en paralelo (pool acotado, ver
 * DocumentAnalysisExecutorConfig) con un presupuesto total de ANALYSIS_TIMEOUT_MS para toda
 * la petición (Veterinary corta a los 60s; aquí 50s por defecto), y agrega el resultado. Un
 * documento que falla queda con {@code error} en su entrada, nunca tumba a los demás; solo
 * si TODOS fallan se responde 503.
 */
@Service
public class VerificationAnalyzeService implements VerificationAnalyzeUseCase {

    private final DocumentAnalysisProperties documentAnalysisProperties;
    private final DocumentAnalysisRateLimiter documentAnalysisRateLimiter;
    private final VerificationDocumentProcessor documentProcessor;
    private final VerificationAggregator aggregator;
    private final ExecutorService documentAnalysisExecutor;
    private final ExecutorService verificationWorkerExecutor;

    public VerificationAnalyzeService(DocumentAnalysisProperties documentAnalysisProperties,
            DocumentAnalysisRateLimiter documentAnalysisRateLimiter, VerificationDocumentProcessor documentProcessor,
            VerificationAggregator aggregator, ExecutorService documentAnalysisExecutor,
            @Qualifier("verificationWorkerExecutor") ExecutorService verificationWorkerExecutor) {
        this.documentAnalysisProperties = documentAnalysisProperties;
        this.documentAnalysisRateLimiter = documentAnalysisRateLimiter;
        this.documentProcessor = documentProcessor;
        this.aggregator = aggregator;
        this.documentAnalysisExecutor = documentAnalysisExecutor;
        this.verificationWorkerExecutor = verificationWorkerExecutor;
    }

    @Override
    public VerificationAnalyzeResponse analyze(VerificationAnalyzeRequest request) {
        documentAnalysisRateLimiter.checkAndRecord();
        validate(request);

        try {
            return documentAnalysisExecutor.submit(() -> doAnalyze(request))
                    .get(documentAnalysisProperties.getAnalysisTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new AnalysisUnavailableException("La verificación superó el tiempo máximo permitido.");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AnalysisUnavailableException("Error inesperado procesando la verificación.", ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AnalysisUnavailableException("Interrumpido procesando la verificación.", ex);
        }
    }

    private VerificationAnalyzeResponse doAnalyze(VerificationAnalyzeRequest request) {
        List<CompletableFuture<VerificationDocumentResult>> futures = request.getDocuments().stream()
                .map(document -> CompletableFuture.supplyAsync(
                        () -> processOne(document, request.getDeclared()), verificationWorkerExecutor))
                .toList();

        List<VerificationDocumentResult> results = futures.stream().map(CompletableFuture::join).toList();

        boolean allFailed = results.stream().allMatch(result -> result.getError() != null);
        if (allFailed) {
            throw new AnalysisUnavailableException("No se pudo procesar ningún documento de la solicitud.");
        }

        VerificationAggregator.Verdict verdict = aggregator.aggregate(results, request.getDeclared());

        return VerificationAnalyzeResponse.builder()
                .requestId(request.getRequestId())
                .recommendation(verdict.recommendation())
                .riskLevel(verdict.riskLevel())
                .documents(results)
                .build();
    }

    private VerificationDocumentResult processOne(VerificationDocumentRequest document, DeclaredData declared) {
        Optional<DocumentType> type = DocumentType.fromCode(document.getDocumentType());
        if (type.isEmpty()) {
            return VerificationDocumentResult.builder()
                    .documentType(document.getDocumentType())
                    .supported(false)
                    .build();
        }
        return documentProcessor.process(type.get(), document.getSignedUrl(), declared);
    }

    private void validate(VerificationAnalyzeRequest request) {
        requireNotBlank(request.getRequestId(), "requestId es obligatorio.");
        if (request.getDocuments() == null || request.getDocuments().isEmpty()) {
            throw new DocumentAnalysisValidationException("documents no puede estar vacío.");
        }
        for (VerificationDocumentRequest document : request.getDocuments()) {
            requireNotBlank(document.getDocumentType(), "documentType es obligatorio en cada documento.");
            requireNotBlank(document.getSignedUrl(), "signedUrl es obligatorio en cada documento.");
        }
    }

    private void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new DocumentAnalysisValidationException(message);
        }
    }
}
