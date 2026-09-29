package com.myanimal.org.IA_service.infrastructure.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;

/** Lectura y validación de los args (JSON crudo del modelo) que comparten los tools. */
final class ToolArgs {

    private ToolArgs() {
    }

    static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    static String requireString(Map<String, Object> args, String key) {
        String value = string(args, key);
        if (value == null) {
            throw new ToolExecutionException("Falta el parámetro obligatorio '" + key + "'.");
        }
        return value;
    }

    static Double number(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            throw new ToolExecutionException("El parámetro '" + key + "' debe ser numérico.");
        }
    }

    static UUID uuid(Map<String, Object> args, String key) {
        String value = requireString(args, key);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new ToolExecutionException("El parámetro '" + key + "' debe ser un identificador válido.");
        }
    }

    static LocalDate localDate(Map<String, Object> args, String key) {
        String value = string(args, key);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ToolExecutionException("El parámetro '" + key + "' debe tener formato yyyy-MM-dd.");
        }
    }
}
