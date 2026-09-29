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
import com.myanimal.org.IA_service.infrastructure.adapters.out.petservice.dto.PetCreateRequest;
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
            handleError(ex, "al listar mascotas", "No se pudieron obtener las mascotas del usuario.");
            throw ex; // handleError siempre lanza; esto solo satisface al compilador.
        }
    }

    @Override
    public Pet getPet(UUID petId, String rawJwt) {
        try {
            PetResponse response = petServiceWebClient.get()
                    .uri("/pets/{petId}", petId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawJwt)
                    .retrieve()
                    .bodyToMono(PetResponse.class)
                    .block();
            return toPet(response);
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                throw new UpstreamSessionExpiredException("La sesión expiró al llamar a pet-service.", ex);
            }
            if (ex.getStatusCode().value() == 404) {
                log.warn("pet-service respondió 404 al consultar la mascota {}", petId);
                throw new ToolExecutionException("No se encontró la mascota.");
            }
            log.warn("pet-service respondió {} al consultar una mascota", ex.getStatusCode().value());
            throw new ToolExecutionException("No se pudo verificar la mascota.");
        }
    }

    @Override
    public Pet createPet(Pet pet, String rawJwt) {
        PetCreateRequest request = PetCreateRequest.builder()
                .ownerId(pet.getOwnerId())
                .name(pet.getName())
                .species(pet.getSpecies())
                .breed(pet.getBreed())
                .sex(pet.getSex())
                .birthDate(pet.getBirthDate())
                .height(pet.getHeight())
                .weight(pet.getWeight())
                .build();
        try {
            PetResponse response = petServiceWebClient.post()
                    .uri("/pets")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawJwt)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(PetResponse.class)
                    .block();
            return toPet(response);
        } catch (WebClientResponseException ex) {
            handleError(ex, "al crear una mascota", "No se pudo registrar la mascota. Revisa los datos e intenta de nuevo.");
            throw ex;
        }
    }

    private void handleError(WebClientResponseException ex, String action, String businessMessage) {
        if (ex.getStatusCode().value() == 401) {
            throw new UpstreamSessionExpiredException("La sesión expiró al llamar a pet-service.", ex);
        }
        log.warn("pet-service respondió {} {}", ex.getStatusCode().value(), action);
        throw new ToolExecutionException(businessMessage);
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
