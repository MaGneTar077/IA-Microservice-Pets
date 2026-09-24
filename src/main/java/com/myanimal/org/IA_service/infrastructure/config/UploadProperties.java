package com.myanimal.org.IA_service.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "uploads")
public class UploadProperties {

    private int maxFilesPerRequest = 5;
    private long maxTotalBytes = 15_728_640;
}
