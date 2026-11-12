package com.myanimal.org.IA_service.infrastructure.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

/**
 * WebClient aparte para /internal/verification/analyze, con su propio timeout
 * (gemini.verification-timeout-seconds), independiente del que usa el chat
 * (GeminiWebClientConfig, gemini.timeout-seconds).
 */
@Slf4j
@Configuration
public class GeminiVerificationWebClientConfig {

    @Bean
    public WebClient geminiVerificationWebClient(GeminiProperties properties) {
        log.info("Gemini (verificación) configurado: modelo={} baseUrl={} timeoutSeconds={}",
                properties.getModel(), properties.getBaseUrl(), properties.getVerificationTimeoutSeconds());

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getVerificationTimeoutSeconds() * 1000)
                .responseTimeout(Duration.ofSeconds(properties.getVerificationTimeoutSeconds()));

        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
