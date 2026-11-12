package com.myanimal.org.IA_service.application.service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysis;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysisRequest;
import com.myanimal.org.IA_service.application.dto.DocumentCheck;
import com.myanimal.org.IA_service.application.dto.DocumentExtraction;
import com.myanimal.org.IA_service.application.dto.ExtractedData;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisProperties;

/**
 * Checks y recommendation en código (sección 5 del checkpoint): el LLM solo extrae
 * (DocumentExtraction), nunca decide si algo coincide. typeMatches se calcula aquí
 * comparando detectedType contra el documentType declarado.
 */
@Component
class DocumentCheckEngine {

    private static final String PASS = "PASS";
    private static final String FAIL = "FAIL";
    private static final String UNCLEAR = "UNCLEAR";

    // Pesos DIAN aplicados de derecha a izquierda.
    private static final int[] DIAN_WEIGHTS = { 3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71 };

    private final Clock clock;
    private final DocumentAnalysisProperties properties;

    DocumentCheckEngine(Clock clock, DocumentAnalysisProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    DocumentAnalysis buildAnalysis(DocumentAnalysisRequest request, DocumentExtraction extraction) {
        ExtractedData extracted = extraction.getExtracted();
        DeclaredData declared = request.getDeclared();

        boolean typeMatches = request.getDocumentType().equals(extraction.getDetectedType());

        List<DocumentCheck> checks = new ArrayList<>();
        checks.add(new DocumentCheck("TYPE_MATCHES", typeMatches ? PASS : FAIL,
                "Tipo detectado: " + extraction.getDetectedType() + ", declarado: " + request.getDocumentType()));
        checks.add(nitMatchesCheck(extracted, declared));
        checks.add(nitCheckDigitCheck(extracted));
        checks.add(nameMatchesCheck("NAME_MATCHES", extracted == null ? null : extracted.getLegalName(),
                declared.getClinicName()));
        checks.add(nameMatchesCheck("LEGAL_REP_MATCHES",
                extracted == null ? null : extracted.getLegalRepresentativeName(),
                declared.getLegalRepresentativeName()));
        checks.add(issueDateRecentCheck(extracted));
        checks.add(activityRelatedCheck(extracted));

        String recommendation = computeRecommendation(extraction, typeMatches, checks);

        return DocumentAnalysis.builder()
                .detectedType(extraction.getDetectedType())
                .typeMatches(typeMatches)
                .legible(extraction.getLegible())
                .extracted(extracted)
                .checks(checks)
                .flags(extraction.getFlags())
                .confidence(extraction.getConfidence())
                .summary(extraction.getSummary())
                .recommendation(recommendation)
                .build();
    }

    private DocumentCheck nitMatchesCheck(ExtractedData extracted, DeclaredData declared) {
        String extractedNit = extracted == null ? null : digitsOnly(extracted.getNit());
        String declaredNit = baseNit(declared.getNit());
        if (extractedNit == null || extractedNit.isEmpty() || declaredNit.isEmpty()) {
            return new DocumentCheck("NIT_MATCHES", UNCLEAR, "No hay NIT extraído o declarado para comparar.");
        }
        boolean matches = extractedNit.equals(declaredNit);
        return new DocumentCheck("NIT_MATCHES", matches ? PASS : FAIL,
                "Extraído: " + extractedNit + ", declarado: " + declaredNit);
    }

    private DocumentCheck nitCheckDigitCheck(ExtractedData extracted) {
        if (extracted == null || isBlank(extracted.getNit()) || isBlank(extracted.getVerificationDigit())) {
            return new DocumentCheck("NIT_CHECK_DIGIT_VALID", UNCLEAR,
                    "No hay NIT o dígito de verificación extraído.");
        }
        String nitDigits = digitsOnly(extracted.getNit());
        String verificationDigit = digitsOnly(extracted.getVerificationDigit());
        if (nitDigits.isEmpty() || verificationDigit.isEmpty()) {
            return new DocumentCheck("NIT_CHECK_DIGIT_VALID", UNCLEAR, "El NIT extraído no es numérico.");
        }
        int expected = computeDianCheckDigit(nitDigits);
        boolean matches = Integer.toString(expected).equals(verificationDigit);
        return new DocumentCheck("NIT_CHECK_DIGIT_VALID", matches ? PASS : FAIL,
                "Calculado: " + expected + ", extraído: " + verificationDigit);
    }

    private DocumentCheck nameMatchesCheck(String code, String extractedName, String declaredName) {
        if (isBlank(extractedName) || isBlank(declaredName)) {
            return new DocumentCheck(code, UNCLEAR, "No hay suficiente información para comparar.");
        }
        // El brief es explícito: una coincidencia parcial es UNCLEAR, nunca FAIL.
        boolean matches = normalizeName(extractedName).equals(normalizeName(declaredName));
        return new DocumentCheck(code, matches ? PASS : UNCLEAR,
                "Extraído: " + extractedName + ", declarado: " + declaredName);
    }

    private DocumentCheck issueDateRecentCheck(ExtractedData extracted) {
        if (extracted == null || isBlank(extracted.getIssueDate())) {
            return new DocumentCheck("ISSUE_DATE_RECENT", UNCLEAR, "No hay fecha de expedición extraída.");
        }
        LocalDate issueDate;
        try {
            issueDate = LocalDate.parse(extracted.getIssueDate());
        } catch (DateTimeParseException ex) {
            return new DocumentCheck("ISSUE_DATE_RECENT", UNCLEAR, "La fecha extraída no tiene un formato válido.");
        }
        long ageDays = java.time.temporal.ChronoUnit.DAYS.between(issueDate, LocalDate.now(clock));
        boolean recent = ageDays <= properties.getDocMaxAgeDays();
        return new DocumentCheck("ISSUE_DATE_RECENT", recent ? PASS : FAIL,
                "Antigüedad: " + ageDays + " días, máximo permitido: " + properties.getDocMaxAgeDays());
    }

    private DocumentCheck activityRelatedCheck(ExtractedData extracted) {
        if (extracted == null || isBlank(extracted.getEconomicActivity())) {
            return new DocumentCheck("ACTIVITY_RELATED", UNCLEAR, "No hay actividad económica extraída.");
        }
        // Heurística por palabra clave, no por código CIIU exacto: el brief pide verificar el
        // código vigente antes de fijarlo, y eso no se puede confirmar en este bloque.
        boolean mentionsVeterinary = normalizeName(extracted.getEconomicActivity()).contains("veterinari");
        return new DocumentCheck("ACTIVITY_RELATED", mentionsVeterinary ? PASS : UNCLEAR,
                "Actividad extraída: " + extracted.getEconomicActivity());
    }

    private String computeRecommendation(DocumentExtraction extraction, boolean typeMatches,
            List<DocumentCheck> checks) {
        if (!Boolean.TRUE.equals(extraction.getLegible())) {
            return "NEEDS_REVIEW";
        }

        boolean hasWrongDocumentFlag = extraction.getFlags() != null
                && extraction.getFlags().contains("WRONG_DOCUMENT");
        boolean nitFails = checks.stream()
                .anyMatch(check -> "NIT_MATCHES".equals(check.getCode()) && FAIL.equals(check.getStatus()));
        if (!typeMatches || nitFails || hasWrongDocumentFlag) {
            return "LIKELY_INVALID";
        }

        boolean allPass = checks.stream().allMatch(check -> PASS.equals(check.getStatus()));
        boolean hasFlags = extraction.getFlags() != null && !extraction.getFlags().isEmpty();
        if (allPass && !hasFlags) {
            return "LIKELY_VALID";
        }

        return "NEEDS_REVIEW";
    }

    private int computeDianCheckDigit(String nitDigits) {
        int sum = 0;
        int weightIndex = 0;
        for (int i = nitDigits.length() - 1; i >= 0 && weightIndex < DIAN_WEIGHTS.length; i--, weightIndex++) {
            int digit = nitDigits.charAt(i) - '0';
            sum += digit * DIAN_WEIGHTS[weightIndex];
        }
        int remainder = sum % 11;
        return (remainder == 0 || remainder == 1) ? remainder : 11 - remainder;
    }

    private String baseNit(String declaredNit) {
        if (declaredNit == null) {
            return "";
        }
        int lastDash = declaredNit.lastIndexOf('-');
        String base = lastDash >= 0 ? declaredNit.substring(0, lastDash) : declaredNit;
        return digitsOnly(base);
    }

    private String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeName(String value) {
        String noAccents = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String alnumSpaces = noAccents.toLowerCase().replaceAll("[^a-z0-9 ]", " ");
        String collapsed = alnumSpaces.replaceAll("\\s+", " ").trim();
        for (String suffix : List.of(" sas", " s a s", " ltda", " s a", " ltd", " cia")) {
            if (collapsed.endsWith(suffix)) {
                collapsed = collapsed.substring(0, collapsed.length() - suffix.length()).trim();
            }
        }
        return collapsed;
    }
}
