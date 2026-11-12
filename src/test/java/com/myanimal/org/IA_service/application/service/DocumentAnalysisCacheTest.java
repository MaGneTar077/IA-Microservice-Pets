package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.myanimal.org.IA_service.application.dto.DocumentAnalysis;
import com.myanimal.org.IA_service.application.service.DocumentAnalysisCache.CachedAnalysis;

class DocumentAnalysisCacheTest {

    @Test
    void sinNadaGuardadoDevuelveVacio() {
        DocumentAnalysisCache cache = new DocumentAnalysisCache(Clock.systemUTC());

        assertThat(cache.get("missing")).isEmpty();
    }

    @Test
    void loQueSeGuardaSePuedeLeerDeVuelta() {
        DocumentAnalysisCache cache = new DocumentAnalysisCache(Clock.systemUTC());
        CachedAnalysis value = new CachedAnalysis("gemini-test", DocumentAnalysis.builder().detectedType("RUT").build());

        cache.put("key", value);

        assertThat(cache.get("key")).contains(value);
    }

    @Test
    void despuesDelTtlElValorYaNoEstaDisponible() {
        Instant start = Instant.parse("2026-11-12T12:00:00Z");
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(start);
        Clock movableClock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        DocumentAnalysisCache cache = new DocumentAnalysisCache(movableClock);
        cache.put("key", new CachedAnalysis("gemini-test", DocumentAnalysis.builder().build()));

        now.set(start.plus(Duration.ofMinutes(6)));

        assertThat(cache.get("key")).isEmpty();
    }
}
