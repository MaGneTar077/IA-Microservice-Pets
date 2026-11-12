package com.myanimal.org.IA_service.infrastructure.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hilo aparte para poder aplicar un tiempo máximo real a todo el análisis (descarga +
 * Gemini + checks) con Future.get(timeout) — no cancela la llamada HTTP en curso, solo
 * deja de esperarla, que es lo que necesita este endpoint síncrono.
 */
@Configuration
public class DocumentAnalysisExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService documentAnalysisExecutor() {
        return Executors.newCachedThreadPool();
    }
}
