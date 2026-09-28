package com.myanimal.org.IA_service.infrastructure.adapters.out.petservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
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
import com.myanimal.org.IA_service.domain.model.Pet;

import io.netty.handler.timeout.ReadTimeoutException;
import reactor.core.publisher.Mono;

class PetServiceAdapterTest {

    private static final String PETS_JSON = """
            [
              {
                "id": "3b7f6e2a-1111-4a2b-9c3d-000000000001",
                "ownerId": "3b7f6e2a-1111-4a2b-9c3d-000000000099",
                "name": "Luna",
                "species": "Perro",
                "breed": "Labrador",
                "sex": "FEMALE",
                "birthDate": "2022-01-15",
                "height": 45.5,
                "weight": 12.3
              }
            ]
            """;

    private PetServiceAdapter buildAdapter(Queue<Object> responses) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://pet-service.test")
                .exchangeFunction(request -> {
                    Object next = responses.poll();
                    if (next instanceof Throwable throwable) {
                        return Mono.error(throwable);
                    }
                    return Mono.just((ClientResponse) next);
                })
                .build();
        return new PetServiceAdapter(webClient);
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
                ReadTimeoutException.INSTANCE, HttpMethod.GET,
                URI.create("http://pet-service.test/pets/user/x"), new HttpHeaders());
    }

    @Test
    void listaLasMascotasDelUsuario() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(ok(PETS_JSON)));
        PetServiceAdapter adapter = buildAdapter(responses);

        List<Pet> pets = adapter.listPets(UUID.randomUUID(), "jwt");

        assertThat(pets).hasSize(1);
        assertThat(pets.get(0).getName()).isEqualTo("Luna");
        assertThat(pets.get(0).getBreed()).isEqualTo("Labrador");
    }

    @Test
    void unErrorGenericoDelServicioLanzaToolExecutionException() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(status(HttpStatus.FORBIDDEN)));
        PetServiceAdapter adapter = buildAdapter(responses);

        assertThatThrownBy(() -> adapter.listPets(UUID.randomUUID(), "jwt"))
                .isInstanceOf(ToolExecutionException.class);
    }

    @Test
    void un401LanzaUpstreamSessionExpiredExceptionNoToolExecutionException() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(status(HttpStatus.UNAUTHORIZED)));
        PetServiceAdapter adapter = buildAdapter(responses);

        assertThatThrownBy(() -> adapter.listPets(UUID.randomUUID(), "jwt"))
                .isInstanceOf(UpstreamSessionExpiredException.class);
    }

    @Test
    void unTimeoutSePropagaSinConvertirseEnUnaExcepcionDeNegocio() {
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(timeout()));
        PetServiceAdapter adapter = buildAdapter(responses);

        assertThatThrownBy(() -> adapter.listPets(UUID.randomUUID(), "jwt"))
                .isInstanceOf(WebClientRequestException.class)
                .isNotInstanceOf(ToolExecutionException.class)
                .isNotInstanceOf(UpstreamSessionExpiredException.class);
    }
}
