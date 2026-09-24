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
import com.myanimal.org.IA_service.domain.exception.AttachmentStorageException;
import com.myanimal.org.IA_service.domain.exception.AttachmentsTooLargeException;
import com.myanimal.org.IA_service.domain.exception.ConversationNotFoundException;
import com.myanimal.org.IA_service.domain.exception.InvalidChatRequestException;
import com.myanimal.org.IA_service.domain.exception.TooManyAttachmentsException;
import com.myanimal.org.IA_service.domain.exception.UnsupportedAttachmentTypeException;
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
