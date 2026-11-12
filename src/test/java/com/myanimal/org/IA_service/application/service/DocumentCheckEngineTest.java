package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysis;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysisRequest;
import com.myanimal.org.IA_service.application.dto.DocumentCheck;
import com.myanimal.org.IA_service.application.dto.DocumentExtraction;
import com.myanimal.org.IA_service.application.dto.ExtractedData;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisProperties;

class DocumentCheckEngineTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);
    private final DocumentAnalysisProperties properties = new DocumentAnalysisProperties();
    private final DocumentCheckEngine engine = new DocumentCheckEngine(clock, properties);

    private DocumentAnalysisRequest.DocumentAnalysisRequestBuilder requestBuilder() {
        return DocumentAnalysisRequest.builder()
                .documentId(UUID.randomUUID())
                .veterinaryId(UUID.randomUUID())
                .documentType("RUT")
                .fileUrl("https://storage.test/signed-url")
                .mimeType("application/pdf")
                .sha256("abc123")
                .declared(DeclaredData.builder()
                        .clinicName("Clínica Patitas SAS")
                        .nit("900123456-8")
                        .legalRepresentativeName("Ana Pérez")
                        .build());
    }

    private DocumentExtraction.DocumentExtractionBuilder extractionBuilder() {
        return DocumentExtraction.builder()
                .detectedType("RUT")
                .legible(true)
                .flags(List.of())
                .confidence(0.9)
                .summary("resumen")
                .extracted(ExtractedData.builder()
                        .nit("900123456")
                        .verificationDigit("8")
                        .legalName("Clínica Patitas SAS")
                        .legalRepresentativeName("Ana Pérez")
                        .issueDate("2026-10-20")
                        .economicActivity("Actividades veterinarias")
                        .build());
    }

    private DocumentCheck checkFor(DocumentAnalysis analysis, String code) {
        return analysis.getChecks().stream().filter(c -> c.getCode().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void documentoConsistenteDaLikelyValid() {
        DocumentAnalysis analysis = engine.buildAnalysis(requestBuilder().build(), extractionBuilder().build());

        assertThat(analysis.getRecommendation()).isEqualTo("LIKELY_VALID");
        assertThat(analysis.getTypeMatches()).isTrue();
        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("PASS");
        assertThat(checkFor(analysis, "NIT_CHECK_DIGIT_VALID").getStatus()).isEqualTo("PASS");
        assertThat(checkFor(analysis, "NAME_MATCHES").getStatus()).isEqualTo("PASS");
        assertThat(checkFor(analysis, "LEGAL_REP_MATCHES").getStatus()).isEqualTo("PASS");
        assertThat(checkFor(analysis, "ISSUE_DATE_RECENT").getStatus()).isEqualTo("PASS");
        assertThat(checkFor(analysis, "ACTIVITY_RELATED").getStatus()).isEqualTo("PASS");
    }

    @Test
    void nitDistintoAlDeclaradoDaFailYLikelyInvalid() {
        DocumentExtraction extraction = extractionBuilder()
                .extracted(ExtractedData.builder().nit("999999999").verificationDigit("8").build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(requestBuilder().build(), extraction);

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("FAIL");
        assertThat(analysis.getRecommendation()).isEqualTo("LIKELY_INVALID");
    }

    @Test
    void digitoDeVerificacionIncorrectoDaFail() {
        DocumentExtraction extraction = extractionBuilder()
                .extracted(ExtractedData.builder().nit("900123456").verificationDigit("3").build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(requestBuilder().build(), extraction);

        assertThat(checkFor(analysis, "NIT_CHECK_DIGIT_VALID").getStatus()).isEqualTo("FAIL");
    }

    @Test
    void nombreParecidoPeroNoIgualDaUnclearYNeedsReview() {
        DocumentExtraction extraction = extractionBuilder()
                .extracted(ExtractedData.builder()
                        .nit("900123456").verificationDigit("8")
                        .legalName("Clínica Veterinaria Otro Nombre SAS")
                        .legalRepresentativeName("Ana Pérez")
                        .issueDate("2026-10-20")
                        .economicActivity("Actividades veterinarias")
                        .build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(requestBuilder().build(), extraction);

        assertThat(checkFor(analysis, "NAME_MATCHES").getStatus()).isEqualTo("UNCLEAR");
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void tipoDeDocumentoDistintoAlDeclaradoDaWrongDocumentInvalido() {
        DocumentExtraction extraction = extractionBuilder().detectedType("CAMARA_COMERCIO").build();

        DocumentAnalysis analysis = engine.buildAnalysis(requestBuilder().documentType("RUT").build(), extraction);

        assertThat(analysis.getTypeMatches()).isFalse();
        assertThat(checkFor(analysis, "TYPE_MATCHES").getStatus()).isEqualTo("FAIL");
        assertThat(analysis.getRecommendation()).isEqualTo("LIKELY_INVALID");
    }

    @Test
    void archivoIlegibleSiempreDaNeedsReview() {
        DocumentExtraction extraction = extractionBuilder().legible(false).extracted(null).build();

        DocumentAnalysis analysis = engine.buildAnalysis(requestBuilder().build(), extraction);

        assertThat(analysis.getLegible()).isFalse();
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void flagWrongDocumentDaLikelyInvalidAunqueElTipoCoincida() {
        DocumentExtraction extraction = extractionBuilder().flags(List.of("WRONG_DOCUMENT")).build();

        DocumentAnalysis analysis = engine.buildAnalysis(requestBuilder().build(), extraction);

        assertThat(analysis.getTypeMatches()).isTrue();
        assertThat(analysis.getRecommendation()).isEqualTo("LIKELY_INVALID");
    }

    @Test
    void fechaDeExpedicionMuyAntiguaDaFail() {
        DocumentExtraction extraction = extractionBuilder()
                .extracted(ExtractedData.builder()
                        .nit("900123456").verificationDigit("8")
                        .legalName("Clínica Patitas SAS")
                        .legalRepresentativeName("Ana Pérez")
                        .issueDate("2020-01-01")
                        .economicActivity("Actividades veterinarias")
                        .build())
                .build();
        properties.setDocMaxAgeDays(90);

        DocumentAnalysis analysis = engine.buildAnalysis(requestBuilder().build(), extraction);

        assertThat(checkFor(analysis, "ISSUE_DATE_RECENT").getStatus()).isEqualTo("FAIL");
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void sinDatosExtraidosLosChecksSonUnclear() {
        DocumentExtraction extraction = DocumentExtraction.builder()
                .detectedType("RUT").legible(true).flags(List.of()).confidence(0.5).summary("resumen").build();

        DocumentAnalysis analysis = engine.buildAnalysis(requestBuilder().build(), extraction);

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("UNCLEAR");
        assertThat(checkFor(analysis, "NIT_CHECK_DIGIT_VALID").getStatus()).isEqualTo("UNCLEAR");
        assertThat(checkFor(analysis, "ACTIVITY_RELATED").getStatus()).isEqualTo("UNCLEAR");
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void flagsDeLaExtraccionSePropaganALaRespuesta() {
        DocumentExtraction extraction = extractionBuilder().flags(List.of("INSTRUCTIONS_IN_DOCUMENT")).build();

        DocumentAnalysis analysis = engine.buildAnalysis(requestBuilder().build(), extraction);

        assertThat(analysis.getFlags()).containsExactly("INSTRUCTIONS_IN_DOCUMENT");
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }
}
