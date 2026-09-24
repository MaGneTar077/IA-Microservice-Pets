package com.myanimal.org.IA_service.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class SupabaseWebClientConfig {

    @Bean
    public WebClient supabaseWebClient(SupabaseProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.getUrl())
                .build();
    }
}
