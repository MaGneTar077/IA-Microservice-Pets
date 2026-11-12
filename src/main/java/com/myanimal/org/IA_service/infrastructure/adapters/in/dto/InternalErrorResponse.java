package com.myanimal.org.IA_service.infrastructure.adapters.in.dto;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InternalErrorResponse {

    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    private String code;
}
