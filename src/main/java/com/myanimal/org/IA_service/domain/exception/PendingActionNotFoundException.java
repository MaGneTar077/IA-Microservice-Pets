package com.myanimal.org.IA_service.domain.exception;

public class PendingActionNotFoundException extends RuntimeException {

    public PendingActionNotFoundException(String message) {
        super(message);
    }
}
