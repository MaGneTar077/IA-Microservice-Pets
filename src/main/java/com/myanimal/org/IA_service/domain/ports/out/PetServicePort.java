package com.myanimal.org.IA_service.domain.ports.out;

import java.util.List;
import java.util.UUID;

import com.myanimal.org.IA_service.domain.model.Pet;

public interface PetServicePort {

    List<Pet> listPets(UUID userId, String rawJwt);

    Pet getPet(UUID petId, String rawJwt);

    Pet createPet(Pet pet, String rawJwt);
}
