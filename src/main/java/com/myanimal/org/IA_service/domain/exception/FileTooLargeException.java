package com.myanimal.org.IA_service.domain.exception;

public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(String message) {
        super(message);
    }
}
