package com.myanimal.org.IA_service.domain.exception;

public class AiModelUnavailableException extends RuntimeException {

    public AiModelUnavailableException(String message) {
        super(message);
    }

    public AiModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
