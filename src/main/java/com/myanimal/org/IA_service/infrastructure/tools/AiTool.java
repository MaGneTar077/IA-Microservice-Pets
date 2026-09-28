package com.myanimal.org.IA_service.infrastructure.tools;

import java.util.Map;

import com.myanimal.org.IA_service.domain.model.UserContext;

public interface AiTool {

    String name();

    Map<String, Object> declaration();

    boolean requiresConfirmation();

    Object execute(Map<String, Object> args, UserContext ctx);

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
