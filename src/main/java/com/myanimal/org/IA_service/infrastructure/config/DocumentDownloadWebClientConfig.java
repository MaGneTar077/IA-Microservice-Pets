package com.myanimal.org.IA_service.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient para descargar el archivo de una URL firmada arbitraria (SSRF: ver validación
 * de host/esquema en DocumentAnalysisService antes de llamar al puerto). Sin
 * .followRedirect(true) el cliente reactor-netty no sigue redirecciones por defecto, así
 * que nunca termina en un host fuera de la lista blanca por esa vía.
 */
@Configuration
public class DocumentDownloadWebClientConfig {

    @Bean
    public WebClient documentDownloadWebClient(DocumentAnalysisProperties documentAnalysisProperties) {
        long maxBytes = documentAnalysisProperties.getMaxFileMb() * 1024L * 1024L;
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize((int) maxBytes))
                .build();
        return WebClient.builder().exchangeStrategies(strategies).build();
    }
}
