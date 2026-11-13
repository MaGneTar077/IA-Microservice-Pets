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
import com.myanimal.org.IA_service.application.dto.DocumentCheck;
import com.myanimal.org.IA_service.application.dto.DocumentExtraction;
import com.myanimal.org.IA_service.application.dto.ExtractedData;
import com.myanimal.org.IA_service.domain.model.DocumentType;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisProperties;

/**
 * Checks y recommendation en código: el LLM solo extrae (DocumentExtraction), nunca decide
 * si algo coincide. typeMatches se calcula aquí comparando detectedType contra el
 * documentType del documento.
 *
 * Principio de riskLevel (lo decide VerificationAggregator a partir de esto, documentado
 * también en IA_VERIFICACION_DOCUMENTOS.md): HIGH cuando algo se CONTRADICE, MEDIUM cuando
 * algo FALTA o no se pudo verificar. Un check en FAIL es una contradicción (se leyó y no
 * cuadra); UNCLEAR es "no se pudo verificar", nunca una contradicción.
 *
 * NIT_MATCHES y NIT_CHECK_DIGIT_VALID responden preguntas distintas:
 * - NIT_MATCHES responde "¿es el mismo NIT?" — compara solo el CUERPO (sin el dígito de
 *   verificación). El DV es una función matemática del cuerpo, no aporta identidad: dos
 *   entidades no pueden tener el mismo cuerpo y distinto DV, así que una discrepancia ahí es
 *   siempre un error de transcripción, nunca un NIT distinto.
 * - NIT_CHECK_DIGIT_VALID responde "¿es un NIT válido?" — calcula el DV esperado con el
 *   algoritmo DIAN a partir del cuerpo extraído y lo compara contra el DV que el modelo leyó
 *   del documento.
 *
 * Checks clave (si fallan, el documento es LIKELY_INVALID — "se contradice"):
 * - TYPE_MATCHES FAIL, NIT_MATCHES FAIL o flag WRONG_DOCUMENT.
 * - NIT_CHECK_DIGIT_VALID FAIL, pero SOLO si el DV declarado por la clínica (persona, no
 *   OCR) coincide con el DV que el modelo leyó del documento: dos lecturas independientes
 *   del mismo dígito que coinciden y aun así no cuadran con el cuerpo del NIT significan
 *   que el NIT en sí es inválido, no un error de lectura. Si el DV declarado no coincide
 *   con el extraído (o no hay DV declarado), no hay corroboración — uno de los dos pudo
 *   leerse/transcribirse mal — y el documento queda en NEEDS_REVIEW, no LIKELY_INVALID.
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

    DocumentAnalysis buildAnalysis(DocumentType documentType, DeclaredData declared, DocumentExtraction extraction) {
        if (documentType == DocumentType.LEGAL_REP_ID) {
            return buildLegalRepIdAnalysis(documentType, declared, extraction);
        }
        return buildBusinessDocumentAnalysis(documentType, declared, extraction);
    }

    private DocumentAnalysis buildLegalRepIdAnalysis(DocumentType documentType, DeclaredData declared,
            DocumentExtraction extraction) {
        boolean typeMatches = documentType.name().equals(extraction.getDetectedType());

        List<DocumentCheck> checks = new ArrayList<>();
        checks.add(new DocumentCheck("TYPE_MATCHES", typeMatches ? PASS : FAIL,
                "Tipo detectado: " + extraction.getDetectedType() + ", declarado: " + documentType.name()));
        checks.add(nameMatchesCheck("LEGAL_REP_MATCHES",
                extraction.getExtracted() == null ? null : extraction.getExtracted().getLegalRepresentativeName(),
                declared == null ? null : declared.getLegalRepresentativeName()));

        String recommendation = computeRecommendation(extraction, typeMatches, checks, declared);

        // Privacidad: una cédula no tiene NIT, razón social, fecha de expedición ni
        // actividad económica que verificar — y, sobre todo, su número nunca debe salir de
        // aquí aunque el modelo lo hubiera extraído desobedeciendo el prompt.
        ExtractedData sanitized = ExtractedData.builder()
                .legalRepresentativeName(extraction.getExtracted() == null ? null
                        : extraction.getExtracted().getLegalRepresentativeName())
                .build();

        return DocumentAnalysis.builder()
                .detectedType(extraction.getDetectedType())
                .typeMatches(typeMatches)
                .legible(extraction.getLegible())
                .extracted(sanitized)
                .checks(checks)
                .flags(extraction.getFlags())
                .confidence(extraction.getConfidence())
                .summary(extraction.getSummary())
                .recommendation(recommendation)
                .build();
    }

    private DocumentAnalysis buildBusinessDocumentAnalysis(DocumentType documentType, DeclaredData declared,
            DocumentExtraction extraction) {
        ExtractedData extracted = extraction.getExtracted();

        boolean typeMatches = documentType.name().equals(extraction.getDetectedType());

        List<DocumentCheck> checks = new ArrayList<>();
        checks.add(new DocumentCheck("TYPE_MATCHES", typeMatches ? PASS : FAIL,
                "Tipo detectado: " + extraction.getDetectedType() + ", declarado: " + documentType.name()));
        checks.add(nitMatchesCheck(extracted, declared));
        checks.add(nitCheckDigitCheck(extracted));
        checks.add(nameMatchesCheck("NAME_MATCHES", extracted == null ? null : extracted.getLegalName(),
                declared == null ? null : declared.getLegalName()));
        checks.add(nameMatchesCheck("LEGAL_REP_MATCHES",
                extracted == null ? null : extracted.getLegalRepresentativeName(),
                declared == null ? null : declared.getLegalRepresentativeName()));
        checks.add(issueDateRecentCheck(extracted));
        checks.add(activityRelatedCheck(extracted));

        String recommendation = computeRecommendation(extraction, typeMatches, checks, declared);

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
        String extractedBody = extracted == null ? "" : nitBody(extracted.getNit());
        String declaredBody = declared == null ? "" : nitBody(declared.getNit());
        if (extractedBody.isEmpty() || declaredBody.isEmpty()) {
            return new DocumentCheck("NIT_MATCHES", UNCLEAR, "No hay NIT extraído o declarado para comparar.");
        }

        boolean matches = extractedBody.equals(declaredBody);
        return new DocumentCheck("NIT_MATCHES", matches ? PASS : FAIL,
                "Extraído: " + extractedBody + ", declarado: " + declaredBody);
    }

    /**
     * El NIT declarado llega tal como Veterinary lo guarda: formato confirmado
     * "cuerpo-DV" (p. ej. "900123456-1"), pero se tolera sin guion o sin DV. El cuerpo de
     * un NIT de persona jurídica son 9 dígitos; si digitsOnly trae 10, el último es el DV y
     * se descarta aquí (el DV se compara aparte, nunca como parte de la identidad).
     */
    private String nitBody(String rawNit) {
        String digits = digitsOnly(rawNit);
        return digits.length() == 10 ? digits.substring(0, 9) : digits;
    }

    /**
     * DV tal como lo escribió la clínica al declarar, si lo incluyó. Vacío si no hay DV
     * declarado (nada con qué corroborar la lectura del modelo).
     */
    private String declaredCheckDigit(DeclaredData declared) {
        if (declared == null) {
            return "";
        }
        String digits = digitsOnly(declared.getNit());
        return digits.length() == 10 ? digits.substring(9) : "";
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
            List<DocumentCheck> checks, DeclaredData declared) {
        if (!Boolean.TRUE.equals(extraction.getLegible())) {
            return "NEEDS_REVIEW";
        }

        boolean hasWrongDocumentFlag = extraction.getFlags() != null
                && extraction.getFlags().contains("WRONG_DOCUMENT");
        boolean nitFails = checks.stream()
                .anyMatch(check -> "NIT_MATCHES".equals(check.getCode()) && FAIL.equals(check.getStatus()));

        ExtractedData extracted = extraction.getExtracted();
        String declaredDv = declaredCheckDigit(declared);
        String extractedDv = extracted == null ? "" : digitsOnly(extracted.getVerificationDigit());
        boolean checkDigitCorroborated = !declaredDv.isEmpty() && declaredDv.equals(extractedDv);
        boolean nitCheckDigitFailsCorroborated = checkDigitCorroborated && checks.stream()
                .anyMatch(check -> "NIT_CHECK_DIGIT_VALID".equals(check.getCode()) && FAIL.equals(check.getStatus()));
        if (!typeMatches || nitFails || hasWrongDocumentFlag || nitCheckDigitFailsCorroborated) {
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
