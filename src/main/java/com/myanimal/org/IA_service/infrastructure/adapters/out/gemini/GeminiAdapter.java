package com.myanimal.org.IA_service.infrastructure.adapters.out.gemini;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myanimal.org.IA_service.domain.exception.AiContentBlockedException;
import com.myanimal.org.IA_service.domain.exception.AiModelException;
import com.myanimal.org.IA_service.domain.exception.AiModelUnavailableException;
import com.myanimal.org.IA_service.domain.exception.AiResponseTruncatedException;
import com.myanimal.org.IA_service.domain.model.AiFunctionCall;
import com.myanimal.org.IA_service.domain.model.AiMessage;
import com.myanimal.org.IA_service.domain.model.AiPart;
import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.model.AiRole;
import com.myanimal.org.IA_service.domain.model.AiUsage;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiCandidate;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiContent;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiErrorResponse;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiFunctionCall;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiFunctionResponse;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiGenerateRequest;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiGenerateResponse;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiGenerationConfig;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiInlineData;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiPart;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiResponsePart;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiSystemInstruction;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiTool;
import com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto.GeminiUsageMetadata;
import com.myanimal.org.IA_service.infrastructure.config.GeminiProperties;

import io.netty.handler.timeout.ReadTimeoutException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class GeminiAdapter implements AiModelPort {

    private static final Set<String> BLOCKED_FINISH_REASONS = Set.of(
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII");

    private static final String TRUNCATED_FINISH_REASON = "MAX_TOKENS";

    // Con responseMimeType: application/json, algunos modelos igual envuelven el texto en un
    // bloque de código markdown. Solo se limpia cuando la respuesta ENTERA es un único bloque
    // (no un fragmento dentro de una respuesta de chat normal que incluya código de verdad).
    private static final Pattern MARKDOWN_FENCE_PATTERN =
            Pattern.compile("^```(?:json)?\\s*\\n?(.*?)\\n?```$", Pattern.DOTALL);

    private final WebClient geminiWebClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiAdapter(WebClient geminiWebClient, GeminiProperties properties, ObjectMapper objectMapper) {
        this.geminiWebClient = geminiWebClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
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
            logGeminiError(ex);
            if (isRetryable(ex.getStatusCode())) {
                throw new GeminiRetryableException(ex);
            }
            throw new AiModelException("Gemini respondió con error " + ex.getStatusCode().value(), ex);
        } catch (WebClientRequestException ex) {
            if (isTimeout(ex)) {
                log.warn("Timeout de lectura esperando respuesta de Gemini (modelo={})", model);
                throw new GeminiRetryableException(ex);
            }
            log.warn("Fallo de conexión al llamar a Gemini (modelo={}): {}",
                    model, ex.getMostSpecificCause().getMessage());
            throw new AiModelException("No se pudo contactar con el modelo de IA.", ex);
        }
    }

    private boolean isTimeout(WebClientRequestException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ReadTimeoutException || cause instanceof TimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void logGeminiError(WebClientResponseException ex) {
        log.warn("Gemini respondió {}: {}", ex.getStatusCode().value(), extractErrorMessage(ex));
    }

    private String extractErrorMessage(WebClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "sin detalle";
        }
        try {
            GeminiErrorResponse errorResponse = objectMapper.readValue(body, GeminiErrorResponse.class);
            if (errorResponse.getError() != null && errorResponse.getError().getMessage() != null) {
                return errorResponse.getError().getMessage();
            }
        } catch (JsonProcessingException ex2) {
            // El cuerpo no tiene el formato de error esperado; no se loguea el body crudo.
        }
        return "sin detalle";
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
        String text = extractText(candidate);
        List<AiFunctionCall> functionCalls = extractFunctionCalls(candidate);
        AiUsage usage = extractUsage(response.getUsageMetadata());
        long latencyMs = System.currentTimeMillis() - startedAt;

        // El log va ANTES de cualquier throw: cuando la respuesta se corta o se bloquea es
        // justo cuando hacen falta el finishReason y los tokens para diagnosticar.
        log.info("Llamada a Gemini: modelo={} latenciaMs={} intento={} finishReason={} promptTokens={} "
                        + "outputTokens={} thoughtsTokens={} totalTokens={} functionCalls={}",
                modelUsed, latencyMs, attempt, finishReason, usage.getPromptTokens(), usage.getOutputTokens(),
                usage.getThoughtsTokens(), usage.getTotalTokens(), functionCalls.size());

        if (finishReason != null && BLOCKED_FINISH_REASONS.contains(finishReason)) {
            throw new AiContentBlockedException("La respuesta fue bloqueada por los filtros de seguridad.");
        }

        // Una respuesta truncada ya no es un éxito silencioso. NO se lanza como
        // GeminiRetryableException a propósito: el mismo prompt con el mismo límite se corta
        // igual, así que no se reintenta ni se cae al modelo de fallback — falla de una.
        if (TRUNCATED_FINISH_REASON.equals(finishReason)) {
            log.warn("Gemini cortó la respuesta por límite de tokens: modelo={} outputTokens={} "
                            + "thoughtsTokens={} maxOutputTokens={}",
                    modelUsed, usage.getOutputTokens(), usage.getThoughtsTokens(), properties.getMaxOutputTokens());
            throw new AiResponseTruncatedException("La respuesta del modelo se cortó por límite de tokens.");
        }

        return AiResponse.builder()
                .text(text)
                .finishReason(finishReason)
                .modelUsed(modelUsed)
                .usage(usage)
                .functionCalls(functionCalls)
                .build();
    }

    private String extractText(GeminiCandidate candidate) {
        if (candidate.getContent() == null || candidate.getContent().getParts() == null) {
            return "";
        }
        String text = candidate.getContent().getParts().stream()
                .filter(part -> !Boolean.TRUE.equals(part.getThought()))
                .map(GeminiResponsePart::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
        return stripMarkdownFence(text);
    }

    private String stripMarkdownFence(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        Matcher matcher = MARKDOWN_FENCE_PATTERN.matcher(text.strip());
        return matcher.matches() ? matcher.group(1).strip() : text;
    }

    private List<AiFunctionCall> extractFunctionCalls(GeminiCandidate candidate) {
        if (candidate.getContent() == null || candidate.getContent().getParts() == null) {
            return List.of();
        }
        return candidate.getContent().getParts().stream()
                .filter(part -> part.getFunctionCall() != null)
                .map(part -> new AiFunctionCall(
                        part.getFunctionCall().getId(),
                        part.getFunctionCall().getName(),
                        part.getFunctionCall().getArgs(),
                        part.getThoughtSignature()))
                .toList();
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

    GeminiGenerateRequest toGeminiRequest(AiRequest request) {
        GeminiSystemInstruction systemInstruction = GeminiSystemInstruction.builder()
                .parts(List.of(GeminiPart.builder().text(request.getSystemInstruction()).build()))
                .build();

        List<GeminiContent> contents = request.getMessages().stream()
                .map(this::toGeminiContent)
                .toList();

        List<GeminiTool> tools = (request.getTools() == null || request.getTools().isEmpty())
                ? null
                : List.of(GeminiTool.builder().functionDeclarations(request.getTools()).build());

        // maxOutputTokens va explícito en TODAS las llamadas, no solo en las de documentos:
        // sin él se hereda el default del modelo y una respuesta larga se corta en silencio.
        // Es un techo, no un objetivo — no alarga las respuestas del chat, solo las permite.
        GeminiGenerationConfig generationConfig = GeminiGenerationConfig.builder()
                .maxOutputTokens(properties.getMaxOutputTokens())
                .responseMimeType(request.getResponseMimeType())
                .responseSchema(request.getResponseSchema())
                .build();

        return GeminiGenerateRequest.builder()
                .systemInstruction(systemInstruction)
                .contents(contents)
                .tools(tools)
                .generationConfig(generationConfig)
                .build();
    }

    private GeminiContent toGeminiContent(AiMessage message) {
        List<GeminiPart> parts = message.getParts().stream()
                .map(this::toGeminiPart)
                .toList();

        return GeminiContent.builder()
                .role(message.getRole() == AiRole.MODEL ? "model" : "user")
                .parts(parts)
                .build();
    }

    private GeminiPart toGeminiPart(AiPart part) {
        if (part.getFunctionCallName() != null) {
            return GeminiPart.builder()
                    .functionCall(GeminiFunctionCall.builder()
                            .id(part.getFunctionCallId())
                            .name(part.getFunctionCallName())
                            .args(part.getFunctionCallArgs())
                            .build())
                    .thoughtSignature(part.getThoughtSignature())
                    .build();
        }
        if (part.getFunctionResponseName() != null) {
            return GeminiPart.builder()
                    .functionResponse(GeminiFunctionResponse.builder()
                            .id(part.getFunctionResponseId())
                            .name(part.getFunctionResponseName())
                            .response(part.getFunctionResponseData())
                            .build())
                    .build();
        }
        if (part.getData() != null) {
            return GeminiPart.builder()
                    .inlineData(GeminiInlineData.builder()
                            .mimeType(part.getMimeType())
                            .data(Base64.getEncoder().encodeToString(part.getData()))
                            .build())
                    .build();
        }
        return GeminiPart.builder().text(part.getText()).build();
    }

    private static class GeminiRetryableException extends RuntimeException {
        GeminiRetryableException(Throwable cause) {
            super(cause);
        }
    }
}
