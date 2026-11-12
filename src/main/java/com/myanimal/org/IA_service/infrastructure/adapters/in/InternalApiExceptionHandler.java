package com.myanimal.org.IA_service.infrastructure.adapters.in;

import java.time.Instant;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.myanimal.org.IA_service.domain.exception.AiContentBlockedException;
import com.myanimal.org.IA_service.domain.exception.AiModelException;
import com.myanimal.org.IA_service.domain.exception.AiModelUnavailableException;
import com.myanimal.org.IA_service.domain.exception.AiResponseTruncatedException;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisValidationException;
import com.myanimal.org.IA_service.domain.exception.FileTooLargeException;
import com.myanimal.org.IA_service.domain.exception.FileUrlNotAllowedException;
import com.myanimal.org.IA_service.domain.exception.UnsupportedFileTypeException;
import com.myanimal.org.IA_service.infrastructure.adapters.in.dto.InternalErrorResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Formato de error distinto del resto de /api/ai/** (ver ErrorResponse): Veterinary pidió
 * {timestamp, status, error, message, code} en vez de {error, message}. Se mantiene aparte
 * para no romper el contrato que ya consume el móvil.
 *
 * {@code @Order(HIGHEST_PRECEDENCE)} es necesario: sin él, para las excepciones Ai* que
 * también maneja el ApiExceptionHandler global (sin assignableTypes, aplica a todos los
 * controllers), Spring puede resolver el handler de ese advice en vez de este para
 * DocumentAnalysisController, devolviendo el shape {error, message} en vez del nuestro.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = DocumentAnalysisController.class)
public class InternalApiExceptionHandler {

    @ExceptionHandler(DocumentAnalysisValidationException.class)
    public ResponseEntity<InternalErrorResponse> handleValidation(DocumentAnalysisValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<InternalErrorResponse> handleMalformedBody(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "El cuerpo de la solicitud es inválido.");
    }

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ResponseEntity<InternalErrorResponse> handleUnsupportedFileType(UnsupportedFileTypeException ex) {
        return build(HttpStatus.BAD_REQUEST, "UNSUPPORTED_MIME_TYPE", ex.getMessage());
    }

    @ExceptionHandler(FileUrlNotAllowedException.class)
    public ResponseEntity<InternalErrorResponse> handleFileUrlNotAllowed(FileUrlNotAllowedException ex) {
        return build(HttpStatus.BAD_REQUEST, "FILE_URL_NOT_ALLOWED", ex.getMessage());
    }

    @ExceptionHandler(FileTooLargeException.class)
    public ResponseEntity<InternalErrorResponse> handleFileTooLarge(FileTooLargeException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", ex.getMessage());
    }

    @ExceptionHandler(AnalysisUnavailableException.class)
    public ResponseEntity<InternalErrorResponse> handleUnavailable(AnalysisUnavailableException ex) {
        log.error("Análisis de documento no disponible: {}", ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_UNAVAILABLE",
                "El análisis de documentos no está disponible en este momento.");
    }

    // Gemini caído, con tiempo agotado o bloqueado: igual que cualquier otro fallo de IA en
    // este endpoint, Veterinary solo necesita saber "no disponible, sigue con la revisión
    // manual" (sección 1 y 8 del checkpoint) — no distingue por qué falló puntualmente.
    @ExceptionHandler({ AiModelException.class, AiModelUnavailableException.class,
            AiContentBlockedException.class, AiResponseTruncatedException.class })
    public ResponseEntity<InternalErrorResponse> handleModelFailure(RuntimeException ex) {
        log.error("Fallo del modelo de IA analizando un documento: {}", ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_UNAVAILABLE",
                "El análisis de documentos no está disponible en este momento.");
    }

    private ResponseEntity<InternalErrorResponse> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new InternalErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(),
                        message, code));
    }
}
