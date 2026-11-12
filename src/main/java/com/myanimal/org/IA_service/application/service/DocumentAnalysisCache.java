package com.myanimal.org.IA_service.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.application.dto.DocumentAnalysis;

/**
 * Idempotencia por sha256+documentType (sección 8 del checkpoint): un reintento de
 * Veterinary con el mismo documento no debería pagar una segunda llamada a Gemini. TTL
 * corto fijo en código, no viene en la tabla de variables de entorno del checkpoint.
 */
@Component
class DocumentAnalysisCache {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final Clock clock;
    private final Map<String, CachedEntry> entries = new ConcurrentHashMap<>();

    DocumentAnalysisCache(Clock clock) {
        this.clock = clock;
    }

    Optional<CachedAnalysis> get(String key) {
        CachedEntry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now(clock).isAfter(entry.expiresAt())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    void put(String key, CachedAnalysis value) {
        entries.put(key, new CachedEntry(value, Instant.now(clock).plus(TTL)));
    }

    record CachedAnalysis(String model, DocumentAnalysis analysis) {
    }

    private record CachedEntry(CachedAnalysis value, Instant expiresAt) {
    }
}
