package com.myanimal.org.IA_service.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "documents")
public class DocumentsProperties {

    private long maxFileSizeBytes;
    private String schemaPath;
    private String promptPath;
}
