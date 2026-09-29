package com.myanimal.org.IA_service.infrastructure.adapters.out.medicalservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.exception.UpstreamSessionExpiredException;
import com.myanimal.org.IA_service.domain.model.VaccineRecord;

import io.netty.handler.timeout.ReadTimeoutException;
import reactor.core.publisher.Mono;

class MedicalServiceAdapterTest {

    private MedicalServiceAdapter buildAdapter(Queue<Object> responses) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://medical-service.test")
                .exchangeFunction(request -> {
                    Object next = responses.poll();
                    if (next instanceof Throwable throwable) {
                        return Mono.error(throwable);
                    }
                    return Mono.just((ClientResponse) next);
                })
                .build();
        return new MedicalServiceAdapter(webClient);
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

    private VaccineRecord sampleRecord() {
        return new VaccineRecord(UUID.randomUUID(), "Rabia", "LOT-1", Instant.parse("2026-01-15T00:00:00Z"),
                null, "Dr. Pérez", null);
    }

    @Test
    void registraLaVacunaCorrectamente() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(ok("{\"id\":\"abc\"}")));
        MedicalServiceAdapter adapter = buildAdapter(responses);

        var result = adapter.registerVaccine(sampleRecord(), "jwt");

        assertThat(result).containsEntry("id", "abc");
    }

    @Test
    void un401LanzaUpstreamSessionExpiredException() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(status(HttpStatus.UNAUTHORIZED)));
        MedicalServiceAdapter adapter = buildAdapter(responses);

        assertThatThrownBy(() -> adapter.registerVaccine(sampleRecord(), "jwt"))
                .isInstanceOf(UpstreamSessionExpiredException.class);
    }

    @Test
    void unErrorDeValidacionLanzaToolExecutionException() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(status(HttpStatus.BAD_REQUEST)));
        MedicalServiceAdapter adapter = buildAdapter(responses);

        assertThatThrownBy(() -> adapter.registerVaccine(sampleRecord(), "jwt"))
                .isInstanceOf(ToolExecutionException.class);
    }

    @Test
    void unTimeoutSePropagaSinConvertirseEnUnaExcepcionDeNegocio() {
        WebClientRequestException timeout = new WebClientRequestException(
                ReadTimeoutException.INSTANCE, HttpMethod.POST,
                URI.create("http://medical-service.test/api/vaccines"), new HttpHeaders());
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(timeout));
        MedicalServiceAdapter adapter = buildAdapter(responses);

        assertThatThrownBy(() -> adapter.registerVaccine(sampleRecord(), "jwt"))
                .isInstanceOf(WebClientRequestException.class)
                .isNotInstanceOf(ToolExecutionException.class)
                .isNotInstanceOf(UpstreamSessionExpiredException.class);
    }
}
