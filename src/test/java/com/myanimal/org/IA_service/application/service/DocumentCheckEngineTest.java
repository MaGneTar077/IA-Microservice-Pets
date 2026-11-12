package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysis;
import com.myanimal.org.IA_service.application.dto.DocumentCheck;
import com.myanimal.org.IA_service.application.dto.DocumentExtraction;
import com.myanimal.org.IA_service.application.dto.ExtractedData;
import com.myanimal.org.IA_service.domain.model.DocumentType;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisProperties;

class DocumentCheckEngineTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);
    private final DocumentAnalysisProperties properties = new DocumentAnalysisProperties();
    private final DocumentCheckEngine engine = new DocumentCheckEngine(clock, properties);

    private DeclaredData.DeclaredDataBuilder declaredBuilder() {
        return DeclaredData.builder()
                .legalName("Clínica Patitas SAS")
                .nit("900123456-8")
                .legalRepresentativeName("Ana Pérez");
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
        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredBuilder().build(),
                extractionBuilder().build());

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
    void nitMatchesComparaDigitosSinImportarSiElDeclaradoTraeDigitoDeVerificacionOGuion() {
        // Declarado sin guion y sin dígito de verificación: solo el cuerpo del NIT.
        DeclaredData declaredSinDigito = declaredBuilder().nit("900123456").build();
        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredSinDigito,
                extractionBuilder().build());

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("PASS");
    }

    @Test
    void nitMatchesComparaDigitosConDigitoDeVerificacionPegadoSinGuion() {
        DeclaredData declaredPegado = declaredBuilder().nit("9001234568").build();
        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredPegado,
                extractionBuilder().build());

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("PASS");
    }

    @Test
    void nitDistintoAlDeclaradoDaFailYLikelyInvalid() {
        DocumentExtraction extraction = extractionBuilder()
                .extracted(ExtractedData.builder().nit("999999999").verificationDigit("8").build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredBuilder().build(), extraction);

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("FAIL");
        assertThat(analysis.getRecommendation()).isEqualTo("LIKELY_INVALID");
    }

    @Test
    void digitoDeVerificacionIncorrectoConNitMatchesPassEscalaALikelyInvalid() {
        // Declarado sin dígito de verificación, para que NIT_MATCHES solo compare el cuerpo
        // del NIT y corrobore la lectura sin que el dígito incorrecto lo arrastre a FAIL.
        DeclaredData declaredSinDigito = declaredBuilder().nit("900123456").build();
        DocumentExtraction extraction = extractionBuilder()
                .extracted(ExtractedData.builder().nit("900123456").verificationDigit("3").build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredSinDigito, extraction);

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("PASS");
        assertThat(checkFor(analysis, "NIT_CHECK_DIGIT_VALID").getStatus()).isEqualTo("FAIL");
        assertThat(analysis.getRecommendation()).isEqualTo("LIKELY_INVALID");
    }

    @Test
    void digitoDeVerificacionIncorrectoSinCorroboracionNoEscalaSoloPorSiMismo() {
        // Sin NIT declarado no hay corroboración de la lectura: NIT_MATCHES queda UNCLEAR
        // y el fallo del dígito de verificación podría ser solo OCR, así que no es
        // "clave" por sí solo (NEEDS_REVIEW, no LIKELY_INVALID).
        DocumentExtraction extraction = extractionBuilder()
                .extracted(ExtractedData.builder().nit("900123456").verificationDigit("3").build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, null, extraction);

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("UNCLEAR");
        assertThat(checkFor(analysis, "NIT_CHECK_DIGIT_VALID").getStatus()).isEqualTo("FAIL");
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
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

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredBuilder().build(), extraction);

        assertThat(checkFor(analysis, "NAME_MATCHES").getStatus()).isEqualTo("UNCLEAR");
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void tipoDeDocumentoDistintoAlDeclaradoDaLikelyInvalid() {
        DocumentExtraction extraction = extractionBuilder().detectedType("CHAMBER_OF_COMMERCE").build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredBuilder().build(), extraction);

        assertThat(analysis.getTypeMatches()).isFalse();
        assertThat(checkFor(analysis, "TYPE_MATCHES").getStatus()).isEqualTo("FAIL");
        assertThat(analysis.getRecommendation()).isEqualTo("LIKELY_INVALID");
    }

    @Test
    void archivoIlegibleSiempreDaNeedsReview() {
        DocumentExtraction extraction = extractionBuilder().legible(false).extracted(null).build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredBuilder().build(), extraction);

        assertThat(analysis.getLegible()).isFalse();
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void flagWrongDocumentDaLikelyInvalidAunqueElTipoCoincida() {
        DocumentExtraction extraction = extractionBuilder().flags(List.of("WRONG_DOCUMENT")).build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredBuilder().build(), extraction);

        assertThat(analysis.getTypeMatches()).isTrue();
        assertThat(analysis.getRecommendation()).isEqualTo("LIKELY_INVALID");
    }

    @Test
    void fechaDeExpedicionMuyAntiguaDaFailYNeedsReview() {
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

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredBuilder().build(), extraction);

        assertThat(checkFor(analysis, "ISSUE_DATE_RECENT").getStatus()).isEqualTo("FAIL");
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void sinDeclaredLosChecksQueLoNecesitanSonUnclear() {
        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, null, extractionBuilder().build());

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("UNCLEAR");
        assertThat(checkFor(analysis, "NAME_MATCHES").getStatus()).isEqualTo("UNCLEAR");
        assertThat(checkFor(analysis, "LEGAL_REP_MATCHES").getStatus()).isEqualTo("UNCLEAR");
        // No dependen de declared: siguen computándose.
        assertThat(checkFor(analysis, "TYPE_MATCHES").getStatus()).isEqualTo("PASS");
        assertThat(checkFor(analysis, "ISSUE_DATE_RECENT").getStatus()).isEqualTo("PASS");
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void sinDatosExtraidosLosChecksSonUnclear() {
        DocumentExtraction extraction = DocumentExtraction.builder()
                .detectedType("RUT").legible(true).flags(List.of()).confidence(0.5).summary("resumen").build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredBuilder().build(), extraction);

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("UNCLEAR");
        assertThat(checkFor(analysis, "NIT_CHECK_DIGIT_VALID").getStatus()).isEqualTo("UNCLEAR");
        assertThat(checkFor(analysis, "ACTIVITY_RELATED").getStatus()).isEqualTo("UNCLEAR");
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void flagsDeLaExtraccionSePropaganALaRespuesta() {
        DocumentExtraction extraction = extractionBuilder().flags(List.of("INSTRUCTIONS_IN_DOCUMENT")).build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaredBuilder().build(), extraction);

        assertThat(analysis.getFlags()).containsExactly("INSTRUCTIONS_IN_DOCUMENT");
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void legalRepIdSoloEvaluaTipoYNombreDelRepresentante() {
        DocumentExtraction extraction = DocumentExtraction.builder()
                .detectedType("LEGAL_REP_ID")
                .legible(true)
                .flags(List.of())
                .confidence(0.9)
                .summary("resumen")
                .extracted(ExtractedData.builder().legalRepresentativeName("Ana Pérez").build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.LEGAL_REP_ID, declaredBuilder().build(),
                extraction);

        assertThat(analysis.getChecks()).extracting(DocumentCheck::getCode)
                .containsExactly("TYPE_MATCHES", "LEGAL_REP_MATCHES");
        assertThat(checkFor(analysis, "LEGAL_REP_MATCHES").getStatus()).isEqualTo("PASS");
        assertThat(analysis.getRecommendation()).isEqualTo("LIKELY_VALID");
    }

    @Test
    void legalRepIdNuncaDevuelveElNumeroDeCedulaAunqueElModeloLoHayaExtraido() {
        // El modelo desobedeció el prompt y puso un número de cédula en "nit" — defensa en
        // profundidad: el engine lo descarta igual, sin importar qué haya devuelto Gemini.
        DocumentExtraction extraction = DocumentExtraction.builder()
                .detectedType("LEGAL_REP_ID")
                .legible(true)
                .flags(List.of())
                .confidence(0.9)
                .summary("Cédula 1 234 567 890 de Ana Pérez.")
                .extracted(ExtractedData.builder()
                        .legalRepresentativeName("Ana Pérez")
                        .nit("1234567890")
                        .verificationDigit("5")
                        .issueDate("2020-01-01")
                        .economicActivity("N/A")
                        .build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.LEGAL_REP_ID, declaredBuilder().build(),
                extraction);

        assertThat(analysis.getExtracted().getNit()).isNull();
        assertThat(analysis.getExtracted().getVerificationDigit()).isNull();
        assertThat(analysis.getExtracted().getIssueDate()).isNull();
        assertThat(analysis.getExtracted().getEconomicActivity()).isNull();
        assertThat(analysis.getExtracted().getLegalRepresentativeName()).isEqualTo("Ana Pérez");
    }
}
