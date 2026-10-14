package com.myanimal.org.IA_service.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.StreamUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class DocumentExtractionConfig {

    @Bean("medicalDocumentSchema")
    public Map<String, Object> medicalDocumentSchema(DocumentsProperties properties,
            ResourcePatternResolver resourceResolver, ObjectMapper objectMapper) throws IOException {
        Resource resource = resourceResolver.getResource(properties.getSchemaPath());
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<Map<String, Object>>() {
            });
        }
    }

    @Bean("documentExtractionPromptText")
    public String documentExtractionPromptText(DocumentsProperties properties,
            ResourcePatternResolver resourceResolver) throws IOException {
        Resource resource = resourceResolver.getResource(properties.getPromptPath());
        try (InputStream inputStream = resource.getInputStream()) {
            return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
        }
    }
}
