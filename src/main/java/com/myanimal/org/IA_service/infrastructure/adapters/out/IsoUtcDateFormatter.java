package com.myanimal.org.IA_service.infrastructure.adapters.out;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * pet-service, medical-service y calendar-service son del mismo compañero: todos esperan
 * "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" (UTC con milisegundos) en el cuerpo de sus peticiones y
 * responden 500 con un offset explícito (ej. "-05:00") aunque sea ISO-8601 válido para el
 * mismo instante — confirmado en calendar-service, tratado igual en medical-service por
 * prevención. Nunca se confía en la serialización por defecto de Jackson para
 * java.time.Instant al armar estos DTOs de salida.
 */
public final class IsoUtcDateFormatter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private IsoUtcDateFormatter() {
    }

    public static String format(Instant instant) {
        return instant == null ? null : FORMATTER.format(instant);
    }
}
