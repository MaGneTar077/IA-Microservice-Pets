package com.myanimal.org.IA_service.infrastructure.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;

@Component
public class CrearMascotaTool implements AiTool {

    private final PetServicePort petServicePort;

    public CrearMascotaTool(PetServicePort petServicePort) {
        this.petServicePort = petServicePort;
    }

    @Override
    public String name() {
        return "crear_mascota";
    }

    @Override
    public Map<String, Object> declaration() {
        return Map.of(
                "name", name(),
                "description", "Registra una mascota nueva para el usuario autenticado.",
                "parameters", Map.of(
                        "type", "OBJECT",
                        "properties", Map.ofEntries(
                                Map.entry("name", Map.of(
                                        "type", "STRING", "description", "Nombre de la mascota")),
                                Map.entry("species", Map.of(
                                        "type", "STRING", "description", "Especie, ej. Perro, Gato")),
                                Map.entry("breed", Map.of(
                                        "type", "STRING", "description", "Raza, texto libre")),
                                Map.entry("sex", Map.of(
                                        "type", "STRING", "enum", List.of("MALE", "FEMALE"))),
                                Map.entry("birthDate", Map.of(
                                        "type", "STRING", "description", "Fecha de nacimiento, formato yyyy-MM-dd")),
                                Map.entry("height", Map.of(
                                        "type", "NUMBER", "description", "Altura en centímetros")),
                                Map.entry("weight", Map.of(
                                        "type", "NUMBER", "description", "Peso en kilogramos"))),
                        "required", List.of("name", "species")));
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public String describe(Map<String, Object> args) {
        String name = ToolArgs.string(args, "name");
        String species = ToolArgs.string(args, "species");
        String breed = ToolArgs.string(args, "breed");

        StringBuilder description = new StringBuilder("Registrar a ").append(name != null ? name : "la mascota");
        if (species != null || breed != null) {
            description.append(" (");
            if (species != null) {
                description.append(species);
            }
            if (breed != null) {
                description.append(species != null ? ", " : "").append(breed);
            }
            description.append(")");
        }
        return description.toString();
    }

    /**
     * Sin llamadas a pet-service: no hace falta verificar propiedad (el dueño siempre es el
     * usuario autenticado), solo que los campos obligatorios estén y los opcionales tengan
     * el formato correcto — para no dejarle al usuario una tarjeta que va a fallar al
     * confirmar por un birthDate mal formado, por ejemplo.
     */
    @Override
    public Map<String, Object> validate(Map<String, Object> args, UserContext ctx) {
        ToolArgs.requireString(args, "name");
        ToolArgs.requireString(args, "species");
        ToolArgs.localDate(args, "birthDate");
        ToolArgs.number(args, "height");
        ToolArgs.number(args, "weight");
        return args;
    }

    @Override
    public Object execute(Map<String, Object> args, UserContext ctx) {
        String name = ToolArgs.requireString(args, "name");
        String species = ToolArgs.requireString(args, "species");
        String breed = ToolArgs.string(args, "breed");
        String sex = ToolArgs.string(args, "sex");
        LocalDate birthDate = ToolArgs.localDate(args, "birthDate");
        Double height = ToolArgs.number(args, "height");
        Double weight = ToolArgs.number(args, "weight");

        Pet toCreate = Pet.builder()
                .ownerId(ctx.userId())
                .name(name)
                .species(species)
                .breed(breed)
                .sex(sex)
                .birthDate(birthDate)
                .height(height)
                .weight(weight)
                .build();

        Pet created = petServicePort.createPet(toCreate, ctx.rawJwt());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", created.getId());
        result.put("name", created.getName());
        result.put("species", created.getSpecies());
        result.put("breed", created.getBreed());
        return result;
    }
}
