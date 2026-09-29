package com.myanimal.org.IA_service.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.exception.UpstreamSessionExpiredException;
import com.myanimal.org.IA_service.domain.model.UserContext;

class ToolExecutorTest {

    private final UserContext userContext = new UserContext(UUID.randomUUID(), "Ana", "jwt");

    @Test
    void nombreDeToolDesconocidoDevuelveErrorSinLanzar() {
        ToolRegistry registry = new ToolRegistry(java.util.List.of());
        ToolExecutor executor = new ToolExecutor(registry);

        ToolResult result = executor.execute("no_existe", Map.of(), userContext);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("no_existe");
    }

    @Test
    void toolQueEjecutaConExitoDevuelveToolResultOk() {
        AiTool tool = mock(AiTool.class);
        when(tool.name()).thenReturn("mi_tool");
        when(tool.execute(any(), any())).thenReturn(Map.of("x", 1));
        ToolRegistry registry = new ToolRegistry(java.util.List.of(tool));
        ToolExecutor executor = new ToolExecutor(registry);

        ToolResult result = executor.execute("mi_tool", Map.of(), userContext);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).isEqualTo(Map.of("x", 1));
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void toolExecutionExceptionSeConvierteEnToolResultDeError() {
        AiTool tool = mock(AiTool.class);
        when(tool.name()).thenReturn("mi_tool");
        when(tool.execute(any(), any())).thenThrow(new ToolExecutionException("la mascota no es del usuario"));
        ToolRegistry registry = new ToolRegistry(java.util.List.of(tool));
        ToolExecutor executor = new ToolExecutor(registry);

        ToolResult result = executor.execute("mi_tool", Map.of(), userContext);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("la mascota no es del usuario");
    }

    @Test
    void unaUpstreamSessionExpiredExceptionSePropagaSinConvertirseEnToolResult() {
        AiTool tool = mock(AiTool.class);
        when(tool.name()).thenReturn("mi_tool");
        when(tool.execute(any(), any()))
                .thenThrow(new UpstreamSessionExpiredException("expiró", new RuntimeException("401")));
        ToolRegistry registry = new ToolRegistry(java.util.List.of(tool));
        ToolExecutor executor = new ToolExecutor(registry);

        assertThatThrownBy(() -> executor.execute("mi_tool", Map.of(), userContext))
                .isInstanceOf(UpstreamSessionExpiredException.class);
    }

    @Test
    void validateDeUnNombreDesconocidoDevuelveErrorSinLanzar() {
        ToolRegistry registry = new ToolRegistry(java.util.List.of());
        ToolExecutor executor = new ToolExecutor(registry);

        ToolResult result = executor.validate("no_existe", Map.of(), userContext);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).contains("no_existe");
    }

    @Test
    void validateQuePasaDevuelveLosArgsEnriquecidosDelTool() {
        AiTool tool = mock(AiTool.class);
        when(tool.name()).thenReturn("mi_tool");
        when(tool.validate(any(), any())).thenReturn(Map.of("startDate", "2026-01-01T10:00:00-05:00"));
        ToolRegistry registry = new ToolRegistry(java.util.List.of(tool));
        ToolExecutor executor = new ToolExecutor(registry);

        ToolResult result = executor.validate("mi_tool", Map.of("startDate", "2026-01-01T10:00:00"), userContext);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).isEqualTo(Map.of("startDate", "2026-01-01T10:00:00-05:00"));
    }

    @Test
    void validateQueLanzaToolExecutionExceptionSeConvierteEnToolResultDeError() {
        AiTool tool = mock(AiTool.class);
        when(tool.name()).thenReturn("mi_tool");
        when(tool.validate(any(), any())).thenThrow(new ToolExecutionException("esa fecha ya pasó"));
        ToolRegistry registry = new ToolRegistry(java.util.List.of(tool));
        ToolExecutor executor = new ToolExecutor(registry);

        ToolResult result = executor.validate("mi_tool", Map.of(), userContext);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("esa fecha ya pasó");
    }

    @Test
    void unaUpstreamSessionExpiredExceptionEnValidateSePropagaSinConvertirseEnToolResult() {
        AiTool tool = mock(AiTool.class);
        when(tool.name()).thenReturn("mi_tool");
        when(tool.validate(any(), any()))
                .thenThrow(new UpstreamSessionExpiredException("expiró", new RuntimeException("401")));
        ToolRegistry registry = new ToolRegistry(java.util.List.of(tool));
        ToolExecutor executor = new ToolExecutor(registry);

        assertThatThrownBy(() -> executor.validate("mi_tool", Map.of(), userContext))
                .isInstanceOf(UpstreamSessionExpiredException.class);
    }
}
