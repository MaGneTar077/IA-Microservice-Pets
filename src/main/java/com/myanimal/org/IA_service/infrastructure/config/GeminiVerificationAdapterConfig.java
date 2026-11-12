package com.myanimal.org.IA_service.infrastructure.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.GeminiAdapter;

/**
 * Segundo AiModelPort, solo para /internal/verification/analyze: mismo modelo y API key que
 * el chat, pero con el WebClient/timeout de GeminiVerificationWebClientConfig, maxRetries=0
 * y modelFallback igual al modelo principal — "sin fallback de modelo" (decisión de
 * negocio), sin tocar la firma pública de GeminiAdapter ni su lógica de reintentos.
 */
@Configuration
public class GeminiVerificationAdapterConfig {

    @Bean(name = "geminiVerificationAdapter")
    public AiModelPort geminiVerificationAdapter(GeminiProperties properties,
            @Qualifier("geminiVerificationWebClient") WebClient geminiVerificationWebClient,
            ObjectMapper objectMapper) {
        GeminiProperties verificationProperties = new GeminiProperties();
        verificationProperties.setApiKey(properties.getApiKey());
        verificationProperties.setModel(properties.getModel());
        verificationProperties.setModelFallback(properties.getModel());
        verificationProperties.setBaseUrl(properties.getBaseUrl());
        verificationProperties.setTimeoutSeconds(properties.getVerificationTimeoutSeconds());
        verificationProperties.setMaxRetries(0);
        verificationProperties.setRetryBackoffMillis(0);
        verificationProperties.setMaxOutputTokens(properties.getMaxOutputTokens());

        return new GeminiAdapter(geminiVerificationWebClient, verificationProperties, objectMapper);
    }
}
