package com.myanimal.org.IA_service.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.StreamUtils;

@Configuration
public class SystemPromptConfig {

    @Bean("systemPromptText")
    public String systemPromptText(ResourcePatternResolver resourceResolver) throws IOException {
        Resource resource = resourceResolver.getResource("classpath:prompts/system-prompt.txt");
        try (InputStream inputStream = resource.getInputStream()) {
            return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
        }
    }
}
