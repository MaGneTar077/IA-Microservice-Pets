package com.myanimal.org.IA_service.infrastructure.tools;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {

    private final Map<String, AiTool> toolsByName;

    public ToolRegistry(List<AiTool> tools) {
        this.toolsByName = tools.stream().collect(Collectors.toUnmodifiableMap(AiTool::name, Function.identity()));
    }

    public List<Map<String, Object>> declarations() {
        return toolsByName.values().stream().map(AiTool::declaration).toList();
    }

    public Optional<AiTool> findByName(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }
}
