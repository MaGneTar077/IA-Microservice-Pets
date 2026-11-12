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
 * Checks clave (si fallan, el documento es LIKELY_INVALID — "se contradice"):
 * - TYPE_MATCHES FAIL, NIT_MATCHES FAIL o flag WRONG_DOCUMENT.
 * - NIT_CHECK_DIGIT_VALID FAIL, pero SOLO si NIT_MATCHES dio PASS: si el NIT declarado
 *   (escrito por una persona) coincide con el extraído, eso corrobora que el modelo leyó
 *   bien ese NIT, así que un dígito de verificación que no cuadra es un problema del NIT
 *   mismo, no un error de lectura. Si NIT_MATCHES no corrobora (UNCLEAR o FAIL), un fallo
 *   del dígito de verificación puede ser solo un error de OCR en esa posición: no escala
 *   solo (NIT_MATCHES FAIL ya escala por su cuenta; UNCLEAR deja el documento en
 *   NEEDS_REVIEW).
 * Limitación conocida y aceptada: si el NIT declarado no incluye el dígito de verificación,
 * NIT_MATCHES corrobora el cuerpo del NIT pero no esa posición, así que un OCR que falle
 * justo ahí podría escalar a LIKELY_INVALID. Es poco probable (un documento borroso suele
 * romper también NIT_MATCHES) y el costo es revisión humana extra, no una decisión
 * automática equivocada.
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

        String recommendation = computeRecommendation(extraction, typeMatches, checks);

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
        String extractedDigits = extracted == null ? "" : digitsOnly(extracted.getNit());
        String declaredDigits = declared == null ? "" : digitsOnly(declared.getNit());
        if (extractedDigits.isEmpty() || declaredDigits.isEmpty()) {
            return new DocumentCheck("NIT_MATCHES", UNCLEAR, "No hay NIT extraído o declarado para comparar.");
        }

        // El NIT declarado llega tal como Veterinary lo guarda: con o sin dígito de
        // verificación, con o sin guion. Coincide si los dígitos declarados son iguales al
        // NIT extraído, o al NIT extraído seguido de su dígito de verificación.
        String checkDigit = extracted == null ? "" : digitsOnly(extracted.getVerificationDigit());
        boolean matches = declaredDigits.equals(extractedDigits)
                || declaredDigits.equals(extractedDigits + checkDigit);
        return new DocumentCheck("NIT_MATCHES", matches ? PASS : FAIL,
                "Extraído: " + extractedDigits + ", declarado: " + declaredDigits);
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
        boolean nitMatchesPassed = checks.stream()
                .anyMatch(check -> "NIT_MATCHES".equals(check.getCode()) && PASS.equals(check.getStatus()));
        boolean nitFails = checks.stream()
                .anyMatch(check -> "NIT_MATCHES".equals(check.getCode()) && FAIL.equals(check.getStatus()));
        boolean nitCheckDigitFailsCorroborated = nitMatchesPassed && checks.stream()
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
