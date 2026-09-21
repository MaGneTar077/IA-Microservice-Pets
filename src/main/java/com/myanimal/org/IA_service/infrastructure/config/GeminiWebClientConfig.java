package com.myanimal.org.IA_service.infrastructure.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

@Slf4j
@Configuration
public class GeminiWebClientConfig {

    @Bean
    public WebClient geminiWebClient(GeminiProperties properties) {
        log.info("Gemini configurado: modelo={} fallback={} baseUrl={}",
                properties.getModel(), properties.getModelFallback(), properties.getBaseUrl());

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getTimeoutSeconds() * 1000)
                .responseTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));

        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
