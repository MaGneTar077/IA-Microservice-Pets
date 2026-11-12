package com.myanimal.org.IA_service.infrastructure.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "document-analysis")
public class DocumentAnalysisProperties {

    private List<String> allowedFileHosts = List.of();
    private int maxFileMb = 10;
    private int maxPages = 20;
    private int docMaxAgeDays = 90;
    // Presupuesto total de /internal/verification/analyze completo (varios documentos en
    // paralelo), no de un documento individual: Veterinary corta a los 60s.
    private long analysisTimeoutMs = 50000;
}
