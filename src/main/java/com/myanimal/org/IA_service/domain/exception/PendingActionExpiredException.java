package com.myanimal.org.IA_service.domain.exception;

public class PendingActionExpiredException extends RuntimeException {

    public PendingActionExpiredException(String message) {
        super(message);
    }
}
