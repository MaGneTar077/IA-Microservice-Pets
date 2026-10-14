package com.myanimal.org.IA_service.infrastructure.adapters.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myanimal.org.IA_service.domain.exception.AiContentBlockedException;
import com.myanimal.org.IA_service.domain.exception.AiModelException;
import com.myanimal.org.IA_service.domain.exception.AiModelUnavailableException;
import com.myanimal.org.IA_service.domain.exception.AiResponseTruncatedException;
import com.myanimal.org.IA_service.domain.model.AiMessage;
import com.myanimal.org.IA_service.domain.model.AiPart;
import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.model.AiRole;
import com.myanimal.org.IA_service.infrastructure.config.GeminiProperties;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

class GeminiAdapterTest {

    private static final String OK_BODY = """
            {
              "candidates": [
                {
                  "content": { "role": "model", "parts": [ { "text": "Hola, ¿en qué puedo ayudarte?" } ] },
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 5,
                "candidatesTokenCount": 3,
                "thoughtsTokenCount": 2,
                "totalTokenCount": 10
              },
              "modelVersion": "gemini-test"
            }
            """;

    private static final String OK_BODY_WITH_THOUGHT = """
            {
              "candidates": [
                {
                  "content": { "role": "model", "parts": [
                    { "text": "pensando en la respuesta", "thought": true },
                    { "text": "Respuesta final" }
                  ]},
                  "finishReason": "STOP"
                }
              ]
            }
            """;

    private static final String SAFETY_BODY = """
            {
              "candidates": [
                { "content": { "role": "model", "parts": [] }, "finishReason": "SAFETY" }
              ]
            }
            """;

    /**
     * Respuesta cortada por límite de tokens: el patrón real que se vio con un uroanálisis —
     * el razonamiento se come el presupuesto (thoughtsTokens alto) y el JSON queda a medias.
     */
    private static final String MAX_TOKENS_BODY = """
            {
              "candidates": [
                {
                  "content": { "role": "model", "parts": [
                    { "text": "{\\"documentType\\":\\"EXAM\\",\\"confianzaGeneral\\":\\"ALTA\\"}" }
                  ]},
                  "finishReason": "MAX_TOKENS"
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 1200,
                "candidatesTokenCount": 900,
                "thoughtsTokenCount": 8000,
                "totalTokenCount": 10100
              }
            }
            """;

    private static final String JSON_WRAPPED_IN_MARKDOWN_FENCE_BODY = """
            {
              "candidates": [
                {
                  "content": { "role": "model", "parts": [
                    { "text": "```json\\n{\\"documentType\\":\\"UNKNOWN\\"}\\n```" }
                  ]},
                  "finishReason": "STOP"
                }
              ]
            }
            """;

    private AtomicInteger callCount;

    private GeminiAdapter buildAdapter(GeminiProperties properties, Queue<Object> responses) {
        callCount = new AtomicInteger(0);
        WebClient webClient = WebClient.builder()
                .baseUrl("http://gemini.test")
                .exchangeFunction(request -> {
                    callCount.incrementAndGet();
                    Object next = responses.poll();
                    if (next instanceof Throwable throwable) {
                        return Mono.error(throwable);
                    }
                    return Mono.just((ClientResponse) next);
                })
                .build();
        return new GeminiAdapter(webClient, properties, new ObjectMapper());
    }

    private GeminiProperties defaultProperties() {
        GeminiProperties properties = new GeminiProperties();
        properties.setApiKey("test-key");
        properties.setModel("gemini-primary");
        properties.setModelFallback("gemini-fallback");
        properties.setBaseUrl("http://gemini.test");
        properties.setTimeoutSeconds(5);
        properties.setMaxRetries(3);
        properties.setRetryBackoffMillis(0);
        return properties;
    }

    private ClientResponse ok(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private ClientResponse status(HttpStatus status) {
        return ClientResponse.create(status).build();
    }

    private WebClientRequestException timeout() {
        return new WebClientRequestException(
                ReadTimeoutException.INSTANCE, HttpMethod.POST, URI.create("http://gemini.test/timeout"),
                new HttpHeaders());
    }

    private AiRequest sampleRequest() {
        return AiRequest.builder()
                .systemInstruction("system")
                .messages(List.of(AiMessage.builder()
                        .role(AiRole.USER)
                        .parts(List.of(AiPart.builder().text("hola").build()))
                        .build()))
                .build();
    }

    @Test
    void mapeaUnAiPartBinarioAInlineDataEnBase64() {
        GeminiAdapter adapter = new GeminiAdapter(
                WebClient.builder().baseUrl("http://gemini.test").build(), defaultProperties(), new ObjectMapper());
        byte[] photoBytes = { 1, 2, 3, 4 };

        AiRequest request = AiRequest.builder()
                .systemInstruction("system")
                .messages(List.of(AiMessage.builder()
                        .role(AiRole.USER)
                        .parts(List.of(
                                AiPart.ofText("¿qué raza es?"),
                                AiPart.ofBinary("image/jpeg", photoBytes)))
                        .build()))
                .build();

        var geminiRequest = adapter.toGeminiRequest(request);
        var parts = geminiRequest.getContents().get(0).getParts();

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).getText()).isEqualTo("¿qué raza es?");
        assertThat(parts.get(0).getInlineData()).isNull();
        assertThat(parts.get(1).getText()).isNull();
        assertThat(parts.get(1).getInlineData().getMimeType()).isEqualTo("image/jpeg");
        assertThat(parts.get(1).getInlineData().getData()).isEqualTo(Base64.getEncoder().encodeToString(photoBytes));
    }

    @Test
    void respuesta200MapeaTextoYTokens() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(ok(OK_BODY)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        AiResponse response = adapter.generate(sampleRequest());

        assertThat(response.getText()).isEqualTo("Hola, ¿en qué puedo ayudarte?");
        assertThat(response.getModelUsed()).isEqualTo("gemini-primary");
        assertThat(response.getFinishReason()).isEqualTo("STOP");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(5);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(3);
        assertThat(response.getUsage().getThoughtsTokens()).isEqualTo(2);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(10);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void partsConThoughtSeIgnoran() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(ok(OK_BODY_WITH_THOUGHT)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        AiResponse response = adapter.generate(sampleRequest());

        assertThat(response.getText()).isEqualTo("Respuesta final");
        assertThat(response.getUsage().getPromptTokens()).isZero();
    }

    @Test
    void exitoTrasReintentos() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(
                status(HttpStatus.SERVICE_UNAVAILABLE),
                status(HttpStatus.SERVICE_UNAVAILABLE),
                ok(OK_BODY)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        AiResponse response = adapter.generate(sampleRequest());

        assertThat(response.getModelUsed()).isEqualTo("gemini-primary");
        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    void reintentosAgotadosUsaFallback() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(
                status(HttpStatus.SERVICE_UNAVAILABLE),
                status(HttpStatus.SERVICE_UNAVAILABLE),
                status(HttpStatus.SERVICE_UNAVAILABLE),
                status(HttpStatus.SERVICE_UNAVAILABLE),
                ok(OK_BODY)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        AiResponse response = adapter.generate(sampleRequest());

        assertThat(response.getModelUsed()).isEqualTo("gemini-fallback");
        assertThat(callCount.get()).isEqualTo(5);
    }

    @Test
    void error403NoReintentaYLanzaExcepcionInmediata() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(status(HttpStatus.FORBIDDEN)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        assertThatThrownBy(() -> adapter.generate(sampleRequest()))
                .isInstanceOf(AiModelException.class);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void generationConfigLlegaConResponseMimeTypeYResponseSchemaCuandoSeSolicitan() {
        GeminiAdapter adapter = new GeminiAdapter(
                WebClient.builder().baseUrl("http://gemini.test").build(), defaultProperties(), new ObjectMapper());
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("documentType", Map.of("type", "string")));

        AiRequest request = AiRequest.builder()
                .systemInstruction("extrae")
                .messages(List.of(AiMessage.builder()
                        .role(AiRole.USER)
                        .parts(List.of(AiPart.ofBinary("application/pdf", new byte[] { 1, 2, 3 })))
                        .build()))
                .responseMimeType("application/json")
                .responseSchema(schema)
                .build();

        var geminiRequest = adapter.toGeminiRequest(request);

        assertThat(geminiRequest.getGenerationConfig()).isNotNull();
        assertThat(geminiRequest.getGenerationConfig().getResponseMimeType()).isEqualTo("application/json");
        assertThat(geminiRequest.getGenerationConfig().getResponseSchema()).isEqualTo(schema);
    }

    @Test
    void maxOutputTokensViajaSiempreAunqueNoSeSoliciteResponseSchema() {
        GeminiProperties properties = defaultProperties();
        properties.setMaxOutputTokens(12345);
        GeminiAdapter adapter = new GeminiAdapter(
                WebClient.builder().baseUrl("http://gemini.test").build(), properties, new ObjectMapper());

        var geminiRequest = adapter.toGeminiRequest(sampleRequest());

        assertThat(geminiRequest.getGenerationConfig()).isNotNull();
        assertThat(geminiRequest.getGenerationConfig().getMaxOutputTokens()).isEqualTo(12345);
        assertThat(geminiRequest.getGenerationConfig().getResponseMimeType()).isNull();
        assertThat(geminiRequest.getGenerationConfig().getResponseSchema()).isNull();
    }

    @Test
    void finishReasonMaxTokensLanzaTruncadaSinReintentarNiUsarFallback() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(
                ok(MAX_TOKENS_BODY),
                ok(OK_BODY)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        assertThatThrownBy(() -> adapter.generate(sampleRequest()))
                .isInstanceOf(AiResponseTruncatedException.class);
        // Una sola llamada: no se reintenta ni se cae al fallback, porque el mismo prompt
        // con el mismo límite se cortaría igual.
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void truncadaEsUnAiModelExceptionParaQueChatLoopRunnerLaSigaAtrapando() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(ok(MAX_TOKENS_BODY)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        assertThatThrownBy(() -> adapter.generate(sampleRequest()))
                .isInstanceOf(AiModelException.class);
    }

    @Test
    void logueaFinishReasonYLosTokensAntesDeFallarPorTruncamiento() {
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(ok(MAX_TOKENS_BODY)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        try {
            assertThatThrownBy(() -> adapter.generate(sampleRequest()))
                    .isInstanceOf(AiResponseTruncatedException.class);

            assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.INFO
                    && event.getFormattedMessage().contains("finishReason=MAX_TOKENS")
                    && event.getFormattedMessage().contains("outputTokens=900")
                    && event.getFormattedMessage().contains("thoughtsTokens=8000"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void logueaFinishReasonEnUnaLlamadaNormal() {
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(ok(OK_BODY)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        try {
            adapter.generate(sampleRequest());

            assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.INFO
                    && event.getFormattedMessage().contains("finishReason=STOP"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void jsonEnvueltoEnFenceDeMarkdownSeLimpiaAntesDeDevolverElTexto() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(ok(JSON_WRAPPED_IN_MARKDOWN_FENCE_BODY)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        AiResponse response = adapter.generate(sampleRequest());

        assertThat(response.getText()).isEqualTo("{\"documentType\":\"UNKNOWN\"}");
    }

    @Test
    void finishReasonSafetyLanzaContenidoBloqueado() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(ok(SAFETY_BODY)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        assertThatThrownBy(() -> adapter.generate(sampleRequest()))
                .isInstanceOf(AiContentBlockedException.class);
    }

    @Test
    void logueaCodigoYMensajeDeErrorSinExponerLaApiKeyNiElBody() {
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        String errorBody = """
                {
                  "error": {
                    "code": 403,
                    "message": "API key not valid. Please pass a valid API key.",
                    "status": "PERMISSION_DENIED"
                  }
                }
                """;
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(
                ClientResponse.create(HttpStatus.FORBIDDEN)
                        .header("Content-Type", "application/json")
                        .body(errorBody)
                        .build()));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        try {
            assertThatThrownBy(() -> adapter.generate(sampleRequest()))
                    .isInstanceOf(AiModelException.class);

            assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("403")
                    && event.getFormattedMessage().contains("API key not valid. Please pass a valid API key."));
            assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("test-key"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void timeoutSeguidoDeExitoEnReintento() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(timeout(), ok(OK_BODY)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        AiResponse response = adapter.generate(sampleRequest());

        assertThat(response.getModelUsed()).isEqualTo("gemini-primary");
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void timeoutEnTodosLosIntentosIncluidoFallbackTerminaEnNoDisponible() {
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        GeminiProperties properties = defaultProperties();
        properties.setMaxRetries(1);
        // 1 intento inicial + 1 reintento al modelo principal, luego 1 intento de fallback: 3 timeouts.
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(timeout(), timeout(), timeout()));
        GeminiAdapter adapter = buildAdapter(properties, responses);

        try {
            assertThatThrownBy(() -> adapter.generate(sampleRequest()))
                    .isInstanceOf(AiModelUnavailableException.class);
            assertThat(callCount.get()).isEqualTo(3);

            assertThat(appender.list).allMatch(event -> !event.getFormattedMessage().contains("test-key"));
            assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().contains("Timeout de lectura"));
            assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("Fallo de conexión"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void fallbackTambienFallaLanzaNoDisponible() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(
                status(HttpStatus.SERVICE_UNAVAILABLE),
                status(HttpStatus.SERVICE_UNAVAILABLE),
                status(HttpStatus.SERVICE_UNAVAILABLE),
                status(HttpStatus.SERVICE_UNAVAILABLE),
                status(HttpStatus.SERVICE_UNAVAILABLE)));
        GeminiAdapter adapter = buildAdapter(defaultProperties(), responses);

        assertThatThrownBy(() -> adapter.generate(sampleRequest()))
                .isInstanceOf(AiModelUnavailableException.class);
        assertThat(callCount.get()).isEqualTo(5);
    }
}
