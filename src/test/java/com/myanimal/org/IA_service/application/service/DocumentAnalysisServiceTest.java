package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysisRequest;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysisResponse;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisValidationException;

class DocumentAnalysisServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T17:00:00Z"), ZoneOffset.UTC);
    private final DocumentAnalysisService service = new DocumentAnalysisService(clock);

    private DocumentAnalysisRequest.DocumentAnalysisRequestBuilder validRequest() {
        return DocumentAnalysisRequest.builder()
                .documentId(UUID.randomUUID())
                .veterinaryId(UUID.randomUUID())
                .documentType("RUT")
                .fileUrl("https://storage.test/signed-url")
                .mimeType("application/pdf")
                .sha256("abc123")
                .declared(DeclaredData.builder().clinicName("Clínica Patitas").nit("900123456-7").build());
    }

    @Test
    void tipoDeDocumentoNoSoportadoDevuelveSupportedFalseSinAnalisis() {
        DocumentAnalysisRequest request = validRequest().documentType("CEDULA").build();

        DocumentAnalysisResponse response = service.analyze(request);

        assertThat(response.isSupported()).isFalse();
        assertThat(response.getAnalysis()).isNull();
        assertThat(response.getDocumentId()).isEqualTo(request.getDocumentId());
        assertThat(response.getAnalyzedAt()).isEqualTo(Instant.parse("2026-11-12T17:00:00Z"));
    }

    @Test
    void tipoSoportadoAunNoImplementadoLanzaAnalysisUnavailable() {
        DocumentAnalysisRequest request = validRequest().build();

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(AnalysisUnavailableException.class);
    }

    @Test
    void sinDocumentIdLanzaValidationException() {
        DocumentAnalysisRequest request = validRequest().documentId(null).build();

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(DocumentAnalysisValidationException.class);
    }

    @Test
    void sinFileUrlLanzaValidationException() {
        DocumentAnalysisRequest request = validRequest().fileUrl(" ").build();

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(DocumentAnalysisValidationException.class);
    }

    @Test
    void sinDeclaredLanzaValidationException() {
        DocumentAnalysisRequest request = validRequest().declared(null).build();

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(DocumentAnalysisValidationException.class);
    }
}
