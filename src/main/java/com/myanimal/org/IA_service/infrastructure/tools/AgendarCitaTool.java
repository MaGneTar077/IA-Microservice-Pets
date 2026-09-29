package com.myanimal.org.IA_service.infrastructure.tools;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.model.CalendarEventRecord;
import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.out.CalendarServicePort;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;
import com.myanimal.org.IA_service.infrastructure.config.AppProperties;

@Component
public class AgendarCitaTool implements AiTool {

    private static final String DEFAULT_EVENT_TYPE = "VET_APPOINTMENT";

    private final PetServicePort petServicePort;
    private final CalendarServicePort calendarServicePort;
    private final AppProperties appProperties;
    private final Clock clock;

    public AgendarCitaTool(PetServicePort petServicePort, CalendarServicePort calendarServicePort,
            AppProperties appProperties, Clock clock) {
        this.petServicePort = petServicePort;
        this.calendarServicePort = calendarServicePort;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "agendar_cita";
    }

    @Override
    public Map<String, Object> declaration() {
        return Map.of(
                "name", name(),
                "description", "Agenda una cita o evento en el calendario del usuario autenticado. Las fechas "
                        + "sin zona explícita se interpretan en la hora local del usuario.",
                "parameters", Map.of(
                        "type", "OBJECT",
                        "properties", Map.ofEntries(
                                Map.entry("title", Map.of("type", "STRING", "description", "Título del evento")),
                                Map.entry("startDate", Map.of(
                                        "type", "STRING", "description", "Fecha y hora de inicio, ISO 8601")),
                                Map.entry("petId", Map.of(
                                        "type", "STRING", "description", "Mascota asociada, si aplica")),
                                Map.entry("description", Map.of("type", "STRING")),
                                Map.entry("eventType", Map.of(
                                        "type", "STRING", "description", "Tipo de evento, por defecto VET_APPOINTMENT")),
                                Map.entry("endDate", Map.of(
                                        "type", "STRING", "description", "Fecha y hora de fin, ISO 8601")),
                                Map.entry("location", Map.of("type", "STRING")),
                                Map.entry("reminderAt", Map.of(
                                        "type", "STRING", "description", "Fecha y hora del recordatorio, ISO 8601"))),
                        "required", List.of("title", "startDate")));
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public String describe(Map<String, Object> args) {
        String title = ToolArgs.string(args, "title");
        String formatted = formatForDisplay(ToolArgs.string(args, "startDate"));
        return "Agendar \"" + (title != null ? title : "cita") + "\""
                + (formatted != null ? " el " + formatted : "");
    }

    @Override
    public Object execute(Map<String, Object> args, UserContext ctx) {
        String title = ToolArgs.requireString(args, "title");
        String rawStartDate = ToolArgs.requireString(args, "startDate");
        String description = ToolArgs.string(args, "description");
        String eventType = ToolArgs.string(args, "eventType");
        if (eventType == null) {
            eventType = DEFAULT_EVENT_TYPE;
        }
        String location = ToolArgs.string(args, "location");

        ZoneId userZone = ZoneId.of(appProperties.getTimezone());
        Instant startDate = normalizeToUtc(rawStartDate, userZone, "startDate");
        if (startDate.isBefore(clock.instant())) {
            throw new ToolExecutionException("Esa fecha ya pasó. Pídele al usuario una fecha futura.");
        }

        String rawEndDate = ToolArgs.string(args, "endDate");
        Instant endDate = rawEndDate != null
                ? normalizeToUtc(rawEndDate, userZone, "endDate")
                : startDate.plus(1, ChronoUnit.HOURS);

        String rawReminderAt = ToolArgs.string(args, "reminderAt");
        Instant reminderAt = rawReminderAt != null
                ? normalizeToUtc(rawReminderAt, userZone, "reminderAt")
                : startDate.minus(1, ChronoUnit.DAYS);

        UUID petId = null;
        if (ToolArgs.string(args, "petId") != null) {
            petId = ToolArgs.uuid(args, "petId");
            Pet pet = petServicePort.getPet(petId, ctx.rawJwt());
            if (!ctx.userId().equals(pet.getOwnerId())) {
                throw new ToolExecutionException("Esa mascota no pertenece al usuario autenticado.");
            }
        }

        CalendarEventRecord record = new CalendarEventRecord(ctx.userId(), petId, title, description, eventType,
                startDate, endDate, location, reminderAt, true);
        return calendarServicePort.createEvent(record, ctx.rawJwt());
    }

    /**
     * Una fecha con offset explícito distinto de "Z" se respeta tal cual (ya viene con la
     * zona correcta). Una fecha sin zona, o con "Z" — la trampa real: el modelo pone "Z"
     * sin pensar en la zona del usuario —, se reinterpreta con esos mismos números de
     * reloj como hora local del usuario y se convierte a UTC de verdad.
     */
    private Instant normalizeToUtc(String raw, ZoneId userZone, String fieldName) {
        try {
            OffsetDateTime withOffset = OffsetDateTime.parse(raw);
            if (!withOffset.getOffset().equals(ZoneOffset.UTC)) {
                return withOffset.toInstant();
            }
            return withOffset.toLocalDateTime().atZone(userZone).toInstant();
        } catch (DateTimeParseException ex) {
            try {
                return LocalDateTime.parse(raw).atZone(userZone).toInstant();
            } catch (DateTimeParseException ex2) {
                throw new ToolExecutionException("El parámetro '" + fieldName + "' debe ser una fecha ISO válida.");
            }
        }
    }

    private String formatForDisplay(String rawDate) {
        if (rawDate == null) {
            return null;
        }
        try {
            ZoneId userZone = ZoneId.of(appProperties.getTimezone());
            Instant instant = normalizeToUtc(rawDate, userZone, "startDate");
            return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(userZone).format(instant);
        } catch (ToolExecutionException ex) {
            return rawDate;
        }
    }
}
