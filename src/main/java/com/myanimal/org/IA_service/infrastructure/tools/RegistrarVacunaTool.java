package com.myanimal.org.IA_service.infrastructure.tools;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.model.VaccineRecord;
import com.myanimal.org.IA_service.domain.ports.out.MedicalServicePort;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;

@Component
public class RegistrarVacunaTool implements AiTool {

    private final PetServicePort petServicePort;
    private final MedicalServicePort medicalServicePort;

    public RegistrarVacunaTool(PetServicePort petServicePort, MedicalServicePort medicalServicePort) {
        this.petServicePort = petServicePort;
        this.medicalServicePort = medicalServicePort;
    }

    @Override
    public String name() {
        return "registrar_vacuna";
    }

    @Override
    public Map<String, Object> declaration() {
        return Map.of(
                "name", name(),
                "description", "Registra una vacuna aplicada a una mascota del usuario autenticado.",
                "parameters", Map.of(
                        "type", "OBJECT",
                        "properties", Map.ofEntries(
                                Map.entry("petId", Map.of(
                                        "type", "STRING", "description", "Identificador de la mascota")),
                                Map.entry("name", Map.of(
                                        "type", "STRING", "description", "Nombre de la vacuna, ej. Rabia")),
                                Map.entry("applicationDate", Map.of(
                                        "type", "STRING", "description", "Fecha de aplicación, ISO 8601")),
                                Map.entry("nextDoseDate", Map.of(
                                        "type", "STRING", "description", "Fecha de la próxima dosis, ISO 8601")),
                                Map.entry("lotNumber", Map.of("type", "STRING")),
                                Map.entry("veterinarian", Map.of("type", "STRING")),
                                Map.entry("notes", Map.of("type", "STRING"))),
                        "required", List.of("petId", "name", "applicationDate")));
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public String describe(Map<String, Object> args) {
        String name = ToolArgs.string(args, "name");
        String formatted = formatDateForDisplay(ToolArgs.string(args, "applicationDate"));
        StringBuilder description = new StringBuilder("Registrar vacuna ")
                .append(name != null ? name : "");
        if (formatted != null) {
            description.append(" (").append(formatted).append(")");
        }
        return description.toString();
    }

    @Override
    public Object execute(Map<String, Object> args, UserContext ctx) {
        UUID petId = ToolArgs.uuid(args, "petId");
        String name = ToolArgs.requireString(args, "name");
        Instant applicationDate = parseIsoOrDate(ToolArgs.requireString(args, "applicationDate"), "applicationDate");
        Instant nextDoseDate = optionalDate(args, "nextDoseDate");
        String lotNumber = ToolArgs.string(args, "lotNumber");
        String veterinarian = ToolArgs.string(args, "veterinarian");
        String notes = ToolArgs.string(args, "notes");

        // Nunca se llama a medical-service sin confirmar antes que la mascota es del usuario.
        Pet pet = petServicePort.getPet(petId, ctx.rawJwt());
        if (!ctx.userId().equals(pet.getOwnerId())) {
            throw new ToolExecutionException("Esa mascota no pertenece al usuario autenticado.");
        }

        VaccineRecord record = new VaccineRecord(petId, name, lotNumber, applicationDate, nextDoseDate,
                veterinarian, notes);
        return medicalServicePort.registerVaccine(record, ctx.rawJwt());
    }

    private Instant optionalDate(Map<String, Object> args, String key) {
        String value = ToolArgs.string(args, key);
        return value == null ? null : parseIsoOrDate(value, key);
    }

    private Instant parseIsoOrDate(String value, String fieldName) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ex2) {
                throw new ToolExecutionException("El parámetro '" + fieldName + "' debe ser una fecha ISO válida.");
            }
        }
    }

    private String formatDateForDisplay(String rawDate) {
        if (rawDate == null) {
            return null;
        }
        try {
            Instant instant = parseIsoOrDate(rawDate, "applicationDate");
            return DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC).format(instant);
        } catch (ToolExecutionException ex) {
            return rawDate;
        }
    }
}
