package com.myanimal.org.IA_service.infrastructure.adapters.out.calendarservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.exception.UpstreamSessionExpiredException;
import com.myanimal.org.IA_service.domain.model.CalendarEventRecord;

import io.netty.handler.timeout.ReadTimeoutException;
import reactor.core.publisher.Mono;

class CalendarServiceAdapterTest {

    private CalendarServiceAdapter buildAdapter(Queue<Object> responses) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://calendar-service.test")
                .exchangeFunction(request -> {
                    Object next = responses.poll();
                    if (next instanceof Throwable throwable) {
                        return Mono.error(throwable);
                    }
                    return Mono.just((ClientResponse) next);
                })
                .build();
        return new CalendarServiceAdapter(webClient);
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

    private CalendarEventRecord sampleRecord() {
        Instant start = Instant.parse("2026-09-29T15:00:00Z");
        return new CalendarEventRecord(UUID.randomUUID(), null, "Cita control", null, "VET_APPOINTMENT",
                start, start.plusSeconds(3600), null, start.minusSeconds(86400), true);
    }

    @Test
    void creaElEventoCorrectamente() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(ok("{\"id\":\"evt-1\"}")));
        CalendarServiceAdapter adapter = buildAdapter(responses);

        var result = adapter.createEvent(sampleRecord(), "jwt");

        assertThat(result).containsEntry("id", "evt-1");
    }

    @Test
    void serializaLasFechasComoIsoUtcConZYMilisegundosNuncaConOffset() {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(ok("{\"id\":\"evt-1\"}")));
        WebClient webClient = WebClient.builder()
                .baseUrl("http://calendar-service.test")
                .exchangeFunction(request -> {
                    MockClientHttpRequest httpRequest = new MockClientHttpRequest(request.method(), request.url());
                    request.writeTo(httpRequest, ExchangeStrategies.withDefaults()).block();
                    capturedBody.set(httpRequest.getBodyAsString().block());
                    return Mono.just((ClientResponse) responses.poll());
                })
                .build();
        CalendarServiceAdapter adapter = new CalendarServiceAdapter(webClient);

        // sampleRecord(): startDate 2026-09-29T15:00:00Z (equivale a las 10:00 en Bogotá,
        // -05:00), endDate +1h, reminderAt -1 día — calendar-service acepta el primer
        // formato y responde 500 con el segundo aunque ambos representen el mismo instante.
        adapter.createEvent(sampleRecord(), "jwt");

        String body = capturedBody.get();
        assertThat(body).contains("\"startDate\":\"2026-09-29T15:00:00.000Z\"");
        assertThat(body).contains("\"endDate\":\"2026-09-29T16:00:00.000Z\"");
        assertThat(body).contains("\"reminderAt\":\"2026-09-28T15:00:00.000Z\"");
        assertThat(body).doesNotContain("-05:00").doesNotContain("+00:00");
    }

    @Test
    void un401LanzaUpstreamSessionExpiredException() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(status(HttpStatus.UNAUTHORIZED)));
        CalendarServiceAdapter adapter = buildAdapter(responses);

        assertThatThrownBy(() -> adapter.createEvent(sampleRecord(), "jwt"))
                .isInstanceOf(UpstreamSessionExpiredException.class);
    }

    @Test
    void unErrorDeValidacionLanzaToolExecutionException() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(status(HttpStatus.BAD_REQUEST)));
        CalendarServiceAdapter adapter = buildAdapter(responses);

        assertThatThrownBy(() -> adapter.createEvent(sampleRecord(), "jwt"))
                .isInstanceOf(ToolExecutionException.class);
    }

    @Test
    void unTimeoutSePropagaSinConvertirseEnUnaExcepcionDeNegocio() {
        WebClientRequestException timeout = new WebClientRequestException(
                ReadTimeoutException.INSTANCE, HttpMethod.POST,
                URI.create("http://calendar-service.test/api/calendar-events"), new HttpHeaders());
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(timeout));
        CalendarServiceAdapter adapter = buildAdapter(responses);

        assertThatThrownBy(() -> adapter.createEvent(sampleRecord(), "jwt"))
                .isInstanceOf(WebClientRequestException.class)
                .isNotInstanceOf(ToolExecutionException.class)
                .isNotInstanceOf(UpstreamSessionExpiredException.class);
    }
}
