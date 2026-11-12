package com.myanimal.org.IA_service.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisRateLimitProperties;

/**
 * Límite de solicitudes por hora (RATELIMIT_DOCS_RPH, ya provisionado desde antes de esta
 * etapa pero sin implementación). Ventana deslizante global: este endpoint es servicio a
 * servicio (Veterinary), no hay un usuario por el que repartir el límite.
 */
@Component
class DocumentAnalysisRateLimiter {

    private final Clock clock;
    private final DocumentAnalysisRateLimitProperties properties;
    private final ConcurrentLinkedDeque<Instant> timestamps = new ConcurrentLinkedDeque<>();

    DocumentAnalysisRateLimiter(Clock clock, DocumentAnalysisRateLimitProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    synchronized void checkAndRecord() {
        Instant now = Instant.now(clock);
        Instant windowStart = now.minus(Duration.ofHours(1));
        while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
            timestamps.pollFirst();
        }
        if (timestamps.size() >= properties.getRequestsPerHour()) {
            throw new AnalysisUnavailableException("Se alcanzó el límite de análisis de documentos por hora.");
        }
        timestamps.addLast(now);
    }
}
