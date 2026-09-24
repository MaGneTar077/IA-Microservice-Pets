package com.myanimal.org.IA_service.domain.exception;

public class InvalidChatRequestException extends RuntimeException {

    public InvalidChatRequestException(String message) {
        super(message);
    }
}
