package com.myanimal.org.IA_service.infrastructure.tools;

import java.util.Map;

import com.myanimal.org.IA_service.domain.model.UserContext;

public interface AiTool {

    String name();

    Map<String, Object> declaration();

    boolean requiresConfirmation();

    Object execute(Map<String, Object> args, UserContext ctx);

    /**
     * Corre ANTES de crear una pending action (tools con requiresConfirmation() == true):
     * mismas validaciones de negocio que execute() (campos obligatorios, fecha en el pasado,
     * propiedad del petId) pero sin escribir nada en el servicio destino. Lanza
     * ToolExecutionException si algo no es válido, igual que execute(). El default no valida
     * nada — solo lo sobrescriben los tools con confirmación.
     * <p>
     * El mapa que devuelve reemplaza a los args originales al crear la pending action: los
     * tools que calculan defaults (ej. agendar_cita con endDate/reminderAt) los agregan aquí
     * para que el usuario los vea en la tarjeta de confirmación.
     */
    default Map<String, Object> validate(Map<String, Object> args, UserContext ctx) {
        return args;
    }

    /**
     * Texto legible para la tarjeta de confirmación del móvil. El default humaniza el
     * nombre técnico del tool (nunca debería llegar tal cual a un humano); los tools que
     * piden confirmación lo sobrescriben con una descripción específica de sus args.
     */
    default String describe(Map<String, Object> args) {
        String humanized = name().replace('_', ' ').trim();
        if (humanized.isEmpty()) {
            return name();
        }
        return Character.toUpperCase(humanized.charAt(0)) + humanized.substring(1);
    }
}
