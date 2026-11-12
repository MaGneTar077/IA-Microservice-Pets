package com.myanimal.org.IA_service.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gemini")
public class GeminiProperties {

    private String apiKey;
    private String model;
    private String modelFallback;
    private String baseUrl;
    private int timeoutSeconds;
    // Timeout dedicado para /internal/verification/analyze: el chat se queda en
    // timeoutSeconds (20s); este camino necesita uno corto para que el presupuesto total de
    // la petición (50s, varios documentos en paralelo) sea alcanzable.
    private int verificationTimeoutSeconds = 10;
    private int maxRetries = 1;
    private long retryBackoffMillis = 1000;
    private int maxOutputTokens = 32768;
    private int maxHistoryMessages = 20;
    private int maxHistoryAttachments = 2;
    private int maxToolIterations = 5;
}
