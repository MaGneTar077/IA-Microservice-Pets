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

    // Formato real confirmado por Veterinary: "900123456-1" (9 dígitos + guion + DV). El DV
    // nunca participa en NIT_MATCHES: solo responde "¿es el mismo cuerpo?".
    @Test
    void nitMatchesIgnoraElDvAunqueElExtraidoNoLoInformePorSeparado() {
        DeclaredData declarado = declaredBuilder().nit("900123456-1").build();
        DocumentExtraction extraccionSinDv = extractionBuilder()
                .extracted(ExtractedData.builder().nit("900123456").build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declarado, extraccionSinDv);

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("PASS");
    }

    @Test
    void nitMatchesIgnoraElDvAunqueElExtraidoLoTraigaPegadoConGuion() {
        DeclaredData declarado = declaredBuilder().nit("900123456-1").build();
        DocumentExtraction extraccionConGuion = extractionBuilder()
                .extracted(ExtractedData.builder().nit("900123456-1").build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declarado, extraccionConGuion);

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

    // Los 3 escenarios de NIT_CHECK_DIGIT_VALID, todos con el cuerpo real "900123456"
    // (cuyo DV correcto, algoritmo DIAN, es 8) y formato declarado "cuerpo-DV" confirmado
    // por Veterinary. La corroboración ahora es DV declarado == DV extraído, no NIT_MATCHES.

    @Test
    void dvDeclaradoConTypoDeLaClinicaNoEscalaNada() {
        // La clínica se equivocó al declarar su propio DV (declaró "-7", el real es "-8"),
        // pero el documento en sí es consistente: el extraído trae el DV correcto (8) y
        // NIT_CHECK_DIGIT_VALID da PASS. El typo de declared no toca NIT_MATCHES (compara
        // solo el cuerpo) ni corrobora nada porque no hay FAIL que corroborar.
        DeclaredData declaradoConTypo = declaredBuilder().nit("900123456-7").build();
        DocumentExtraction extraccionValida = extractionBuilder()
                .extracted(ExtractedData.builder().nit("900123456").verificationDigit("8").build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaradoConTypo, extraccionValida);

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("PASS");
        assertThat(checkFor(analysis, "NIT_CHECK_DIGIT_VALID").getStatus()).isEqualTo("PASS");
        // No escala a LIKELY_INVALID: el typo del DV declarado no afecta NIT_MATCHES (solo
        // compara el cuerpo) y no hay ningún FAIL que corroborar. Queda NEEDS_REVIEW solo
        // porque esta extracción no trae los demás campos (nombre, fecha, actividad), no
        // por el typo.
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void dvDudosoPorOcrSinCorroboracionDaNeedsReview() {
        // El documento real tiene DV válido (8, declarado correctamente por la clínica),
        // pero el modelo lo leyó mal (9): NIT_CHECK_DIGIT_VALID FAIL. El DV declarado (8) no
        // coincide con el extraído (9) — sin corroboración, podría ser el OCR. NEEDS_REVIEW,
        // no LIKELY_INVALID.
        DeclaredData declaradoCorrecto = declaredBuilder().nit("900123456-8").build();
        DocumentExtraction extraccionConOcrDudoso = extractionBuilder()
                .extracted(ExtractedData.builder().nit("900123456").verificationDigit("9").build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaradoCorrecto,
                extraccionConOcrDudoso);

        assertThat(checkFor(analysis, "NIT_CHECK_DIGIT_VALID").getStatus()).isEqualTo("FAIL");
        assertThat(analysis.getRecommendation()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void dvFabricadoCorroboradoPorDeclaradoEscalaALikelyInvalid() {
        // El documento trae un DV (9) que no satisface el algoritmo DIAN para su propio
        // cuerpo (debería ser 8), y la clínica declaró ese mismo dígito equivocado (9):
        // dos lecturas independientes coinciden en que el DV es 9, así que no es un error
        // de lectura — el NIT mismo es inválido. LIKELY_INVALID.
        DeclaredData declaradoConMismoDvInvalido = declaredBuilder().nit("900123456-9").build();
        DocumentExtraction extraccionConDvInvalido = extractionBuilder()
                .extracted(ExtractedData.builder().nit("900123456").verificationDigit("9").build())
                .build();

        DocumentAnalysis analysis = engine.buildAnalysis(DocumentType.RUT, declaradoConMismoDvInvalido,
                extraccionConDvInvalido);

        assertThat(checkFor(analysis, "NIT_MATCHES").getStatus()).isEqualTo("PASS");
        assertThat(checkFor(analysis, "NIT_CHECK_DIGIT_VALID").getStatus()).isEqualTo("FAIL");
        assertThat(analysis.getRecommendation()).isEqualTo("LIKELY_INVALID");
    }

    @Test
    void sinDvDeclaradoNoHayCorroboracionYNoEscala() {
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
