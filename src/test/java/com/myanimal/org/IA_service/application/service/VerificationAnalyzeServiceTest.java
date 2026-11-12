package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.application.dto.DocumentAnalysis;
import com.myanimal.org.IA_service.application.dto.VerificationAnalyzeRequest;
import com.myanimal.org.IA_service.application.dto.VerificationAnalyzeResponse;
import com.myanimal.org.IA_service.application.dto.VerificationDocumentRequest;
import com.myanimal.org.IA_service.application.dto.VerificationDocumentResult;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisValidationException;
import com.myanimal.org.IA_service.domain.model.DocumentType;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisProperties;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisRateLimitProperties;

@ExtendWith(MockitoExtension.class)
class VerificationAnalyzeServiceTest {

    @Mock
    private VerificationDocumentProcessor documentProcessor;

    private DocumentAnalysisProperties properties;
    private ExecutorService outerExecutor;
    private ExecutorService workerExecutor;
    private VerificationAnalyzeService service;

    @BeforeEach
    void setUp() {
        properties = new DocumentAnalysisProperties();
        properties.setAnalysisTimeoutMs(5000);
        outerExecutor = Executors.newSingleThreadExecutor();
        workerExecutor = Executors.newFixedThreadPool(4);
        service = buildService();
    }

    @AfterEach
    void tearDown() {
        outerExecutor.shutdownNow();
        workerExecutor.shutdownNow();
    }

    private VerificationAnalyzeService buildService() {
        DocumentAnalysisRateLimitProperties rateLimitProperties = new DocumentAnalysisRateLimitProperties();
        rateLimitProperties.setRequestsPerHour(1000);
        return new VerificationAnalyzeService(properties,
                new DocumentAnalysisRateLimiter(java.time.Clock.systemUTC(), rateLimitProperties), documentProcessor,
                new VerificationAggregator(), outerExecutor, workerExecutor);
    }

    private VerificationAnalyzeRequest.VerificationAnalyzeRequestBuilder requestBuilder() {
        return VerificationAnalyzeRequest.builder()
                .requestId("req-1")
                .subjectType("CLINIC")
                .documents(List.of(
                        VerificationDocumentRequest.builder().documentType("RUT").signedUrl("https://x/1").build(),
                        VerificationDocumentRequest.builder().documentType("CHAMBER_OF_COMMERCE")
                                .signedUrl("https://x/2").build()));
    }

    @Test
    void procesaLosDocumentosEnParaleloYAgregaElResultado() {
        when(documentProcessor.process(DocumentType.RUT, "https://x/1", null)).thenReturn(
                VerificationDocumentResult.builder().documentType("RUT").supported(true)
                        .analysis(DocumentAnalysis.builder().recommendation("LIKELY_VALID").build()).build());
        when(documentProcessor.process(DocumentType.CHAMBER_OF_COMMERCE, "https://x/2", null)).thenReturn(
                VerificationDocumentResult.builder().documentType("CHAMBER_OF_COMMERCE").supported(true)
                        .analysis(DocumentAnalysis.builder().recommendation("LIKELY_VALID").build()).build());

        VerificationAnalyzeResponse response = service.analyze(requestBuilder().build());

        assertThat(response.getRequestId()).isEqualTo("req-1");
        assertThat(response.getDocuments()).hasSize(2);
        // Sin declared en la solicitud: aunque los documentos sean válidos, queda MEDIUM.
        assertThat(response.getRecommendation()).isEqualTo("NEEDS_ATTENTION");
        assertThat(response.getRiskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void unTipoDeDocumentoDesconocidoQuedaComoNoSoportadoSinLlamarAlProcesador() {
        VerificationAnalyzeRequest request = VerificationAnalyzeRequest.builder()
                .requestId("req-2")
                .documents(List.of(VerificationDocumentRequest.builder().documentType("ALGO_RARO")
                        .signedUrl("https://x/1").build()))
                .build();

        VerificationAnalyzeResponse response = service.analyze(request);

        assertThat(response.getDocuments().get(0).isSupported()).isFalse();
    }

    @Test
    void siTodosLosDocumentosFallanLanzaAnalysisUnavailable() {
        when(documentProcessor.process(any(), any(), any())).thenReturn(
                VerificationDocumentResult.builder().documentType("RUT").supported(true)
                        .error("No se pudo descargar.").build());

        assertThatThrownBy(() -> service.analyze(requestBuilder().build()))
                .isInstanceOf(AnalysisUnavailableException.class);
    }

    @Test
    void siUnDocumentoFallaYOtroNoElGlobalSigueSiendoNeedsAttention() {
        when(documentProcessor.process(DocumentType.RUT, "https://x/1", null)).thenReturn(
                VerificationDocumentResult.builder().documentType("RUT").supported(true)
                        .error("No se pudo descargar.").build());
        when(documentProcessor.process(DocumentType.CHAMBER_OF_COMMERCE, "https://x/2", null)).thenReturn(
                VerificationDocumentResult.builder().documentType("CHAMBER_OF_COMMERCE").supported(true)
                        .analysis(DocumentAnalysis.builder().recommendation("LIKELY_VALID").build()).build());

        VerificationAnalyzeResponse response = service.analyze(requestBuilder().build());

        assertThat(response.getRecommendation()).isEqualTo("NEEDS_ATTENTION");
        assertThat(response.getDocuments()).anyMatch(doc -> doc.getError() != null);
    }

    @Test
    void sinDocumentosLanzaValidationException() {
        VerificationAnalyzeRequest request = VerificationAnalyzeRequest.builder().requestId("req-3").build();

        assertThatThrownBy(() -> service.analyze(request)).isInstanceOf(DocumentAnalysisValidationException.class);
    }

    @Test
    void superarElPresupuestoTotalLanzaAnalysisUnavailable() {
        properties.setAnalysisTimeoutMs(50);
        service = buildService();
        when(documentProcessor.process(any(), any(), any())).thenAnswer(invocation -> {
            TimeUnit.MILLISECONDS.sleep(500);
            return VerificationDocumentResult.builder().documentType("RUT").supported(true)
                    .analysis(DocumentAnalysis.builder().recommendation("LIKELY_VALID").build()).build();
        });

        assertThatThrownBy(() -> service.analyze(requestBuilder().build()))
                .isInstanceOf(AnalysisUnavailableException.class);
    }
}
