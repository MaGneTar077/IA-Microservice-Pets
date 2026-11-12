package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysis;
import com.myanimal.org.IA_service.application.dto.VerificationDocumentResult;

class VerificationAggregatorTest {

    private final VerificationAggregator aggregator = new VerificationAggregator();
    private final DeclaredData declared = DeclaredData.builder().legalName("Clínica Patitas").build();

    private VerificationDocumentResult supported(String type, String recommendation) {
        return VerificationDocumentResult.builder()
                .documentType(type)
                .supported(true)
                .analysis(DocumentAnalysis.builder().recommendation(recommendation).build())
                .build();
    }

    @Test
    void todoOkDaReadyForHumanReviewYLow() {
        var verdict = aggregator.aggregate(
                List.of(supported("RUT", "LIKELY_VALID"), supported("CHAMBER_OF_COMMERCE", "LIKELY_VALID")),
                declared);

        assertThat(verdict.recommendation()).isEqualTo("READY_FOR_HUMAN_REVIEW");
        assertThat(verdict.riskLevel()).isEqualTo("LOW");
    }

    @Test
    void unDocumentoLikelyInvalidDaNeedsAttentionYHigh() {
        var verdict = aggregator.aggregate(
                List.of(supported("RUT", "LIKELY_INVALID"), supported("CHAMBER_OF_COMMERCE", "LIKELY_VALID")),
                declared);

        assertThat(verdict.recommendation()).isEqualTo("NEEDS_ATTENTION");
        assertThat(verdict.riskLevel()).isEqualTo("HIGH");
    }

    @Test
    void unDocumentoNeedsReviewDaNeedsAttentionYMedium() {
        var verdict = aggregator.aggregate(
                List.of(supported("RUT", "NEEDS_REVIEW"), supported("CHAMBER_OF_COMMERCE", "LIKELY_VALID")),
                declared);

        assertThat(verdict.recommendation()).isEqualTo("NEEDS_ATTENTION");
        assertThat(verdict.riskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void sinDeclaredDaNeedsAttentionYMedium() {
        var verdict = aggregator.aggregate(List.of(supported("RUT", "LIKELY_VALID")), null);

        assertThat(verdict.recommendation()).isEqualTo("NEEDS_ATTENTION");
        assertThat(verdict.riskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void ningunDocumentoSoportadoDaNeedsAttentionYMedium() {
        VerificationDocumentResult unsupported = VerificationDocumentResult.builder()
                .documentType("FACADE_PHOTO").supported(false).build();

        var verdict = aggregator.aggregate(List.of(unsupported), declared);

        assertThat(verdict.recommendation()).isEqualTo("NEEDS_ATTENTION");
        assertThat(verdict.riskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void unDocumentoConErrorDaNeedsAttentionYMedium() {
        VerificationDocumentResult failed = VerificationDocumentResult.builder()
                .documentType("RUT").supported(true).error("No se pudo descargar.").build();

        var verdict = aggregator.aggregate(List.of(failed, supported("CHAMBER_OF_COMMERCE", "LIKELY_VALID")),
                declared);

        assertThat(verdict.recommendation()).isEqualTo("NEEDS_ATTENTION");
        assertThat(verdict.riskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void highPesaMasQueMediumCuandoAmbosAplican() {
        VerificationDocumentResult failed = VerificationDocumentResult.builder()
                .documentType("CHAMBER_OF_COMMERCE").supported(true).error("No se pudo descargar.").build();

        var verdict = aggregator.aggregate(List.of(supported("RUT", "LIKELY_INVALID"), failed), declared);

        assertThat(verdict.riskLevel()).isEqualTo("HIGH");
    }
}
