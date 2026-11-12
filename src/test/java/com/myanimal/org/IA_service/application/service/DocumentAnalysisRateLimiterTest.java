package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisRateLimitProperties;

class DocumentAnalysisRateLimiterTest {

    private DocumentAnalysisRateLimitProperties properties(int requestsPerHour) {
        DocumentAnalysisRateLimitProperties properties = new DocumentAnalysisRateLimitProperties();
        properties.setRequestsPerHour(requestsPerHour);
        return properties;
    }

    @Test
    void dentroDelLimitePermiteContinuar() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC);
        DocumentAnalysisRateLimiter limiter = new DocumentAnalysisRateLimiter(clock, properties(2));

        assertThatCode(() -> {
            limiter.checkAndRecord();
            limiter.checkAndRecord();
        }).doesNotThrowAnyException();
    }

    @Test
    void superarElLimiteLanzaAnalysisUnavailable() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC);
        DocumentAnalysisRateLimiter limiter = new DocumentAnalysisRateLimiter(clock, properties(1));

        limiter.checkAndRecord();

        assertThatThrownBy(limiter::checkAndRecord).isInstanceOf(AnalysisUnavailableException.class);
    }
}
