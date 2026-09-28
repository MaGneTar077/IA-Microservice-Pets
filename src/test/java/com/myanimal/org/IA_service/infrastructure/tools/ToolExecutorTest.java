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
}
