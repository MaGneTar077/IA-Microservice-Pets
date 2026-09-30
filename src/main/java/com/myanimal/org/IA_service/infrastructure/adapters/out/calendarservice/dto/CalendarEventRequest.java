package com.myanimal.org.IA_service.infrastructure.adapters.out.calendarservice.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * startDate/endDate/reminderAt son String, no Instant: calendar-service acepta
 * "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" (UTC con milisegundos) y responde 500 con offset explícito
 * (ej. "-05:00") aunque sea ISO-8601 válido. {@code CalendarServiceAdapter} las formatea a
 * mano con ese patrón exacto antes de construir este DTO — no se confía en la serialización
 * por defecto de Jackson para java.time.Instant.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CalendarEventRequest {

    private UUID userId;
    private UUID petId;
    private String title;
    private String description;
    private String eventType;
    private String startDate;
    private String endDate;
    private String location;
    private String reminderAt;
    private boolean reminderEnabled;
}
