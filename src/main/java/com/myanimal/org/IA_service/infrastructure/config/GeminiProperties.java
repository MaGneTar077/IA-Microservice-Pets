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
    private int maxRetries = 3;
    private long retryBackoffMillis = 1000;
}
