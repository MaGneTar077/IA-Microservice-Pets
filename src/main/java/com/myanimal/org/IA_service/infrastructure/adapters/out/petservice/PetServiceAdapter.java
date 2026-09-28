package com.myanimal.org.IA_service.infrastructure.adapters.out.petservice;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.exception.UpstreamSessionExpiredException;
import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;
import com.myanimal.org.IA_service.infrastructure.adapters.out.petservice.dto.PetResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PetServiceAdapter implements PetServicePort {

    private final WebClient petServiceWebClient;

    public PetServiceAdapter(@Qualifier("petServiceWebClient") WebClient petServiceWebClient) {
        this.petServiceWebClient = petServiceWebClient;
    }

    @Override
    public List<Pet> listPets(UUID userId, String rawJwt) {
        try {
            PetResponse[] response = petServiceWebClient.get()
                    .uri("/pets/user/{userId}", userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawJwt)
                    .retrieve()
                    .bodyToMono(PetResponse[].class)
                    .block();
            return response == null ? List.of() : Arrays.stream(response).map(this::toPet).toList();
        } catch (WebClientResponseException ex) {
            // Un 401 a mitad del loop significa que el JWT expiró llamando a pet-service (los
            // tokens duran 1 hora): NO es un error de negocio, no se traduce a functionResponse.
            if (ex.getStatusCode().value() == 401) {
                throw new UpstreamSessionExpiredException(
                        "La sesión expiró al llamar a pet-service.", ex);
            }
            log.warn("pet-service respondió {} al listar mascotas", ex.getStatusCode().value());
            throw new ToolExecutionException("No se pudieron obtener las mascotas del usuario.");
        }
    }

    private Pet toPet(PetResponse response) {
        return Pet.builder()
                .id(response.getId())
                .ownerId(response.getOwnerId())
                .name(response.getName())
                .species(response.getSpecies())
                .breed(response.getBreed())
                .sex(response.getSex())
                .birthDate(response.getBirthDate())
                .height(response.getHeight())
                .weight(response.getWeight())
                .build();
    }
}
