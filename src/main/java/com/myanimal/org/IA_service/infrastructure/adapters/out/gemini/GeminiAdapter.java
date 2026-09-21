package com.myanimal.org.IA_service.infrastructure.adapters.out.gemini;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.myanimal.org.IA_service.domain.exception.AiContentBlockedException;
import com.myanimal.org.IA_service.domain.exception.AiModelException;
import com.myanimal.org.IA_service.domain.exception.AiModelUnavailableException;
import com.myanimal.org.IA_service.domain.model.AiMessage;
import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.model.AiRole;
import com.myanimal.org.IA_service.domain.model.AiUsage;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiCandidate;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiContent;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiGenerateRequest;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiGenerateResponse;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiResponsePart;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiSystemInstruction;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiTextPart;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiUsageMetadata;
import com.myanimal.org.IA_service.infrastructure.config.GeminiProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class GeminiAdapter implements AiModelPort {

    private static final Set<String> BLOCKED_FINISH_REASONS = Set.of(
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII");

    private final WebClient geminiWebClient;
    private final GeminiProperties properties;

    public GeminiAdapter(WebClient geminiWebClient, GeminiProperties properties) {
        this.geminiWebClient = geminiWebClient;
        this.properties = properties;
    }

    @Override
    public AiResponse generate(AiRequest request) {
        GeminiGenerateRequest geminiRequest = toGeminiRequest(request);
        return callWithRetries(geminiRequest);
    }

    private AiResponse callWithRetries(GeminiGenerateRequest geminiRequest) {
        String primaryModel = properties.getModel();
        int attempt = 0;

        while (true) {
            attempt++;
            long startedAt = System.currentTimeMillis();
            try {
                GeminiGenerateResponse response = executeCall(geminiRequest, primaryModel);
                return buildAiResponse(response, primaryModel, startedAt, attempt);
            } catch (GeminiRetryableException ex) {
                if (attempt > properties.getMaxRetries()) {
                    log.warn("Modelo principal {} agotó reintentos ({}), usando fallback {}",
                            primaryModel, properties.getMaxRetries(), properties.getModelFallback());
                    return callFallback(geminiRequest);
                }
                sleepBackoff(attempt);
            }
        }
    }

    private AiResponse callFallback(GeminiGenerateRequest geminiRequest) {
        String fallbackModel = properties.getModelFallback();
        long startedAt = System.currentTimeMillis();
        try {
            GeminiGenerateResponse response = executeCall(geminiRequest, fallbackModel);
            return buildAiResponse(response, fallbackModel, startedAt, 1);
        } catch (GeminiRetryableException ex) {
            throw new AiModelUnavailableException("El asistente de IA no está disponible en este momento.");
        }
    }

    private GeminiGenerateResponse executeCall(GeminiGenerateRequest geminiRequest, String model) {
        try {
            return geminiWebClient.post()
                    .uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(geminiRequest)
                    .retrieve()
                    .bodyToMono(GeminiGenerateResponse.class)
                    .block();
        } catch (WebClientResponseException ex) {
            if (isRetryable(ex.getStatusCode())) {
                throw new GeminiRetryableException(ex);
            }
            throw new AiModelException("Gemini respondió con error " + ex.getStatusCode().value(), ex);
        }
    }

    private boolean isRetryable(HttpStatusCode status) {
        return status.value() == 503 || status.value() == 429;
    }

    private void sleepBackoff(int attempt) {
        long backoffMillis = properties.getRetryBackoffMillis() * (1L << (attempt - 1));
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AiModelUnavailableException("Interrumpido esperando reintento del modelo de IA.", ex);
        }
    }

    private AiResponse buildAiResponse(GeminiGenerateResponse response, String modelUsed, long startedAt, int attempt) {
        List<GeminiCandidate> candidates = response.getCandidates();
        if (candidates == null || candidates.isEmpty()) {
            throw new AiContentBlockedException("La respuesta fue bloqueada por los filtros de seguridad.");
        }

        GeminiCandidate candidate = candidates.get(0);
        String finishReason = candidate.getFinishReason();
        if (finishReason != null && BLOCKED_FINISH_REASONS.contains(finishReason)) {
            throw new AiContentBlockedException("La respuesta fue bloqueada por los filtros de seguridad.");
        }

        String text = extractText(candidate);
        AiUsage usage = extractUsage(response.getUsageMetadata());
        long latencyMs = System.currentTimeMillis() - startedAt;

        log.info("Llamada a Gemini: modelo={} latenciaMs={} intento={} promptTokens={} outputTokens={} "
                        + "thoughtsTokens={} totalTokens={}",
                modelUsed, latencyMs, attempt, usage.getPromptTokens(), usage.getOutputTokens(),
                usage.getThoughtsTokens(), usage.getTotalTokens());

        return AiResponse.builder()
                .text(text)
                .finishReason(finishReason)
                .modelUsed(modelUsed)
                .usage(usage)
                .build();
    }

    private String extractText(GeminiCandidate candidate) {
        if (candidate.getContent() == null || candidate.getContent().getParts() == null) {
            return "";
        }
        return candidate.getContent().getParts().stream()
                .filter(part -> !Boolean.TRUE.equals(part.getThought()))
                .map(GeminiResponsePart::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }

    private AiUsage extractUsage(GeminiUsageMetadata metadata) {
        if (metadata == null) {
            return AiUsage.builder().build();
        }
        return AiUsage.builder()
                .promptTokens(metadata.getPromptTokenCount())
                .outputTokens(metadata.getCandidatesTokenCount())
                .thoughtsTokens(metadata.getThoughtsTokenCount())
                .totalTokens(metadata.getTotalTokenCount())
                .build();
    }

    private GeminiGenerateRequest toGeminiRequest(AiRequest request) {
        GeminiSystemInstruction systemInstruction = GeminiSystemInstruction.builder()
                .parts(List.of(GeminiTextPart.builder().text(request.getSystemInstruction()).build()))
                .build();

        List<GeminiContent> contents = request.getMessages().stream()
                .map(this::toGeminiContent)
                .toList();

        return GeminiGenerateRequest.builder()
                .systemInstruction(systemInstruction)
                .contents(contents)
                .build();
    }

    private GeminiContent toGeminiContent(AiMessage message) {
        List<GeminiTextPart> parts = message.getParts().stream()
                .map(part -> GeminiTextPart.builder().text(part.getText()).build())
                .toList();

        return GeminiContent.builder()
                .role(message.getRole() == AiRole.MODEL ? "model" : "user")
                .parts(parts)
                .build();
    }

    private static class GeminiRetryableException extends RuntimeException {
        GeminiRetryableException(Throwable cause) {
            super(cause);
        }
    }
}
