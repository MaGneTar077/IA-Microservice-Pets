package com.myanimal.org.IA_service.infrastructure.tools;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.model.UserContext;

@Component
public class ToolExecutor {

    private final ToolRegistry toolRegistry;

    public ToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Nunca lanza por un nombre desconocido o una falla de negocio del tool (eso se
     * convierte en un ToolResult de error para que el modelo lo lea). Una
     * UpstreamSessionExpiredException, en cambio, se deja propagar a propósito: no es un
     * error que el modelo deba "leer", corta el flujo completo.
     */
    public ToolResult execute(String name, Map<String, Object> args, UserContext ctx) {
        Optional<AiTool> tool = toolRegistry.findByName(name);
        if (tool.isEmpty()) {
            return new ToolResult(false, null, "No existe un tool llamado '" + name + "'.");
        }
        try {
            Object data = tool.get().execute(args, ctx);
            return new ToolResult(true, data, null);
        } catch (ToolExecutionException ex) {
            return new ToolResult(false, null, ex.getMessage());
        }
    }
}
