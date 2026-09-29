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
import java.util.LinkedHashMap;
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
        PreparedAppointment prepared = prepare(args, ctx);
        CalendarEventRecord record = new CalendarEventRecord(ctx.userId(), prepared.petId(), prepared.title(),
                prepared.description(), prepared.eventType(), prepared.startDate(), prepared.endDate(),
                prepared.location(), prepared.reminderAt(), true);
        return calendarServicePort.createEvent(record, ctx.rawJwt());
    }

    /**
     * Misma validación y normalización que execute() (fecha en el pasado, propiedad del
     * petId), pero sin llamar a calendar-service. Los defaults calculados (endDate,
     * reminderAt, y eventType) se escriben de vuelta en los args para que la pending action
     * los muestre en la tarjeta de confirmación — el usuario no debería confirmar a ciegas
     * a qué hora termina su cita o cuándo le llega el recordatorio.
     * <p>
     * Las fechas se devuelven con el offset explícito de {@code app.timezone} (ej.
     * "-05:00"), nunca en UTC/"Z": si se guardaran como "Z" y el tool las reinterpretara más
     * tarde al confirmar, {@link #normalizeToUtc} las trataría como la "Z sospechosa" del
     * modelo y las desplazaría una segunda vez.
     */
    @Override
    public Map<String, Object> validate(Map<String, Object> args, UserContext ctx) {
        PreparedAppointment prepared = prepare(args, ctx);
        ZoneId userZone = ZoneId.of(appProperties.getTimezone());

        Map<String, Object> enriched = new LinkedHashMap<>(args);
        enriched.put("startDate", toLocalOffsetIso(prepared.startDate(), userZone));
        enriched.put("endDate", toLocalOffsetIso(prepared.endDate(), userZone));
        enriched.put("reminderAt", toLocalOffsetIso(prepared.reminderAt(), userZone));
        enriched.put("eventType", prepared.eventType());
        return enriched;
    }

    private PreparedAppointment prepare(Map<String, Object> args, UserContext ctx) {
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

        return new PreparedAppointment(title, description, eventType, location, startDate, endDate, reminderAt,
                petId);
    }

    private String toLocalOffsetIso(Instant instant, ZoneId userZone) {
        return OffsetDateTime.ofInstant(instant, userZone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private record PreparedAppointment(String title, String description, String eventType, String location,
            Instant startDate, Instant endDate, Instant reminderAt, UUID petId) {
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
