package com.myanimal.org.IA_service.infrastructure.adapters.out.medicalservice.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class VaccineRequest {

    private UUID petId;
    private String name;
    private String lotNumber;
    private Instant applicationDate;
    private Instant nextDoseDate;
    private String veterinarian;
    private String notes;
}
