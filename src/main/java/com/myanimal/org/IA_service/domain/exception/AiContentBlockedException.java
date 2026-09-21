package com.myanimal.org.IA_service.domain.exception;

public class AiContentBlockedException extends RuntimeException {

    public AiContentBlockedException(String message) {
        super(message);
    }

    public AiContentBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
