package com.myanimal.org.IA_service.domain.exception;

public class AnalysisUnavailableException extends RuntimeException {

    public AnalysisUnavailableException(String message) {
        super(message);
    }

    public AnalysisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
