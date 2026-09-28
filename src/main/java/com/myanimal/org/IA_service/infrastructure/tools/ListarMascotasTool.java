package com.myanimal.org.IA_service.infrastructure.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;

@Component
public class ListarMascotasTool implements AiTool {

    private final PetServicePort petServicePort;

    public ListarMascotasTool(PetServicePort petServicePort) {
        this.petServicePort = petServicePort;
    }

    @Override
    public String name() {
        return "listar_mascotas";
    }

    @Override
    public Map<String, Object> declaration() {
        return Map.of(
                "name", name(),
                "description", "Lista las mascotas del usuario autenticado, con su identificador interno.",
                "parameters", Map.of(
                        "type", "OBJECT",
                        "properties", Map.of(),
                        "required", List.of()));
    }

    @Override
    public boolean requiresConfirmation() {
        return false;
    }

    @Override
    public Object execute(Map<String, Object> args, UserContext ctx) {
        List<Pet> pets = petServicePort.listPets(ctx.userId(), ctx.rawJwt());
        return pets.stream().map(this::toSummary).toList();
    }

    private Map<String, Object> toSummary(Pet pet) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", pet.getId());
        summary.put("name", pet.getName());
        summary.put("species", pet.getSpecies());
        summary.put("breed", pet.getBreed());
        summary.put("sex", pet.getSex());
        summary.put("birthDate", pet.getBirthDate());
        return summary;
    }
}
