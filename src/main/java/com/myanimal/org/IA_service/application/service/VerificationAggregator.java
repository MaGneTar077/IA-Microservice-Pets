package com.myanimal.org.IA_service.application.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.VerificationDocumentResult;

/**
 * recommendation/riskLevel global, confirmados con Veterinary (único sitio donde se decide
 * esto — ver IA_VERIFICACION_DOCUMENTOS.md sección 4):
 *
 * - READY_FOR_HUMAN_REVIEW + LOW: todo ok.
 * - NEEDS_ATTENTION + MEDIUM: dudas, o algo no evaluado (sin declared, ningún documento
 *   soportado, algún tipo no soportado en la solicitud, o algún documento con error).
 * - NEEDS_ATTENTION + HIGH: algún documento con un check clave en FAIL
 *   (DocumentAnalysis.recommendation == LIKELY_INVALID ya es exactamente eso, ver
 *   DocumentCheckEngine). HIGH pesa más que MEDIUM si ambos aplican.
 */
@Component
class VerificationAggregator {

    private static final String READY_FOR_HUMAN_REVIEW = "READY_FOR_HUMAN_REVIEW";
    private static final String NEEDS_ATTENTION = "NEEDS_ATTENTION";

    private static final String LOW = "LOW";
    private static final String MEDIUM = "MEDIUM";
    private static final String HIGH = "HIGH";

    record Verdict(String recommendation, String riskLevel) {
    }

    Verdict aggregate(List<VerificationDocumentResult> results, DeclaredData declared) {
        boolean anyKeyFail = results.stream()
                .anyMatch(result -> result.isSupported() && result.getError() == null
                        && "LIKELY_INVALID".equals(result.getAnalysis().getRecommendation()));
        if (anyKeyFail) {
            return new Verdict(NEEDS_ATTENTION, HIGH);
        }

        boolean anySupported = results.stream().anyMatch(VerificationDocumentResult::isSupported);
        boolean anyUnsupported = results.stream().anyMatch(result -> !result.isSupported());
        boolean anyError = results.stream().anyMatch(result -> result.getError() != null);
        boolean anyDoubt = results.stream()
                .anyMatch(result -> result.isSupported() && result.getError() == null
                        && "NEEDS_REVIEW".equals(result.getAnalysis().getRecommendation()));

        if (declared == null || !anySupported || anyUnsupported || anyError || anyDoubt) {
            return new Verdict(NEEDS_ATTENTION, MEDIUM);
        }

        return new Verdict(READY_FOR_HUMAN_REVIEW, LOW);
    }
}
