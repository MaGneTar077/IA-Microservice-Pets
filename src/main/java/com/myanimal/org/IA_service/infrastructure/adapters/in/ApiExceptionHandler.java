package com.myanimal.org.IA_service.infrastructure.adapters.in;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.myanimal.org.IA_service.domain.exception.AiContentBlockedException;
import com.myanimal.org.IA_service.domain.exception.AiModelException;
import com.myanimal.org.IA_service.domain.exception.AiModelUnavailableException;
import com.myanimal.org.IA_service.domain.exception.AiResponseTruncatedException;
import com.myanimal.org.IA_service.domain.exception.AttachmentStorageException;
import com.myanimal.org.IA_service.domain.exception.AttachmentsTooLargeException;
import com.myanimal.org.IA_service.domain.exception.ConversationNotFoundException;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisFailedException;
import com.myanimal.org.IA_service.domain.exception.InvalidChatRequestException;
import com.myanimal.org.IA_service.domain.exception.PetNotFoundException;
import com.myanimal.org.IA_service.domain.exception.PendingActionExpiredException;
import com.myanimal.org.IA_service.domain.exception.PendingActionNotFoundException;
import com.myanimal.org.IA_service.domain.exception.TooManyAttachmentsException;
import com.myanimal.org.IA_service.domain.exception.UnsupportedAttachmentTypeException;
import com.myanimal.org.IA_service.domain.exception.UpstreamSessionExpiredException;
import com.myanimal.org.IA_service.infrastructure.adapters.in.dto.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AiModelUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUnavailable(AiModelUnavailableException ex) {
        log.error("Modelo de IA no disponible", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("MODEL_UNAVAILABLE",
                        "El asistente no está disponible en este momento. Intenta de nuevo más tarde."));
    }

    /**
     * Más específico que el handler de AiModelException (del que hereda), así que Spring
     * elige este: el frontend necesita distinguir "se cortó por tamaño" de "el modelo falló",
     * porque lo primero se arregla con un documento más corto, no reintentando.
     */
    @ExceptionHandler(AiResponseTruncatedException.class)
    public ResponseEntity<ErrorResponse> handleTruncated(AiResponseTruncatedException ex) {
        log.error("La respuesta del modelo se cortó por límite de tokens", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("MODEL_RESPONSE_TRUNCATED",
                        "La respuesta no cupo en el límite de tokens. Intenta con un documento más corto."));
    }

    @ExceptionHandler(AiModelException.class)
    public ResponseEntity<ErrorResponse> handleModelException(AiModelException ex) {
        log.error("Error al comunicarse con el modelo de IA", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("MODEL_ERROR",
                        "Ocurrió un error al procesar tu mensaje. Intenta de nuevo."));
    }

    @ExceptionHandler(AiContentBlockedException.class)
    public ResponseEntity<ErrorResponse> handleBlocked(AiContentBlockedException ex) {
        log.warn("Respuesta bloqueada por filtros de seguridad", ex);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("CONTENT_BLOCKED",
                        "No puedo responder a ese mensaje por motivos de seguridad."));
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleConversationNotFound(ConversationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("CONVERSATION_NOT_FOUND", "La conversación no existe."));
    }

    @ExceptionHandler(UnsupportedAttachmentTypeException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedAttachmentType(UnsupportedAttachmentTypeException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse("UNSUPPORTED_ATTACHMENT_TYPE", ex.getMessage()));
    }

    @ExceptionHandler({ TooManyAttachmentsException.class, AttachmentsTooLargeException.class })
    public ResponseEntity<ErrorResponse> handleAttachmentLimits(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("ATTACHMENT_LIMIT_EXCEEDED", ex.getMessage()));
    }

    @ExceptionHandler(AttachmentStorageException.class)
    public ResponseEntity<ErrorResponse> handleAttachmentStorage(AttachmentStorageException ex) {
        log.error("Error al hablar con Supabase Storage", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("ATTACHMENT_STORAGE_ERROR",
                        "Ocurrió un error al procesar tus archivos adjuntos. Intenta de nuevo."));
    }

    @ExceptionHandler(PendingActionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePendingActionNotFound(PendingActionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("PENDING_ACTION_NOT_FOUND", "La acción no existe."));
    }

    @ExceptionHandler(PendingActionExpiredException.class)
    public ResponseEntity<ErrorResponse> handlePendingActionExpired(PendingActionExpiredException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new ErrorResponse("PENDING_ACTION_EXPIRED", "La acción ya expiró."));
    }

    /**
     * Etapa 3.5, parte C: el único código que el frontend necesita reconocer para saber que
     * debe renovar con su refresh token y repetir la petición (nunca reintentar con otra
     * credencial). ex.getMessage() ya es texto final para el usuario — ChatLoopRunner lo
     * arma ahí, incluyendo qué pasos ya se completaron si los hubo antes de que la sesión
     * expirara a mitad de una confirmación.
     */
    @ExceptionHandler(UpstreamSessionExpiredException.class)
    public ResponseEntity<ErrorResponse> handleSessionExpired(UpstreamSessionExpiredException ex) {
        log.warn("Sesión expirada llamando a un servicio destino: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("TOKEN_EXPIRED", ex.getMessage()));
    }

    @ExceptionHandler(PetNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePetNotFound(PetNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("PET_NOT_FOUND", "La mascota no existe o no pertenece al usuario."));
    }

    @ExceptionHandler(DocumentAnalysisFailedException.class)
    public ResponseEntity<ErrorResponse> handleDocumentAnalysisFailed(DocumentAnalysisFailedException ex) {
        log.error("No se pudo analizar el documento", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("DOCUMENT_ANALYSIS_FAILED",
                        "No se pudo analizar el documento. Intenta de nuevo."));
    }

    @ExceptionHandler(InvalidChatRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidChatRequest(InvalidChatRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Solicitud inválida.");
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_ERROR", message));
    }
}
