package com.myanimal.org.IA_service.infrastructure.adapters.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
