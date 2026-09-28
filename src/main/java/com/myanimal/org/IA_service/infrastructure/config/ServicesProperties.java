package com.myanimal.org.IA_service.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "services")
public class ServicesProperties {

    private String gatewayUrl;
    private String petServiceUrl;
    private String medicalServiceUrl;
    private String calendarServiceUrl;
    private int timeoutSeconds = 10;
}
