package com.myanimal.org.IA_service.domain.exception;

/**
 * Gemini cortó la respuesta por límite de tokens (finishReason MAX_TOKENS).
 * <p>
 * Extiende AiModelException a propósito: ChatLoopRunner ya atrapa esa familia alrededor de
 * la redacción de la respuesta final de una pending action, así que una respuesta truncada
 * justo después de ejecutar un tool sigue cayendo en el reply local en vez de salir como
 * error HTTP con la escritura ya hecha (ver etapa 3 parte E en CLAUDE.md).
 */
public class AiResponseTruncatedException extends AiModelException {

    public AiResponseTruncatedException(String message) {
        super(message);
    }
}
