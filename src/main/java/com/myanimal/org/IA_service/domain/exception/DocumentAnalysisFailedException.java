package com.myanimal.org.IA_service.domain.exception;

public class DocumentAnalysisFailedException extends RuntimeException {

    public DocumentAnalysisFailedException(String message) {
        super(message);
    }

    public DocumentAnalysisFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
