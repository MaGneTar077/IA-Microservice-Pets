package com.myanimal.org.IA_service.infrastructure.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

@Configuration
public class ServicesWebClientConfig {

    @Bean
    public WebClient petServiceWebClient(ServicesProperties properties) {
        return buildClient(properties.getPetServiceUrl(), properties.getTimeoutSeconds());
    }

    @Bean
    public WebClient medicalServiceWebClient(ServicesProperties properties) {
        return buildClient(properties.getMedicalServiceUrl(), properties.getTimeoutSeconds());
    }

    @Bean
    public WebClient calendarServiceWebClient(ServicesProperties properties) {
        return buildClient(properties.getCalendarServiceUrl(), properties.getTimeoutSeconds());
    }

    private WebClient buildClient(String baseUrl, int timeoutSeconds) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutSeconds * 1000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
