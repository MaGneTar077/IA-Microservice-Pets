package com.myanimal.org.IA_service.infrastructure.adapters.out.medicalservice.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * applicationDate/nextDoseDate son String, no Instant: mismo compañero que
 * calendar-service (ver CalendarEventRequest) — se sospecha que también rechaza offsets
 * explícitos y solo acepta "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'". MedicalServiceAdapter las
 * formatea con IsoUtcDateFormatter antes de construir este DTO.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class VaccineRequest {

    private UUID userId;
    private UUID petId;
    private String name;
    private String lotNumber;
    private String applicationDate;
    private String nextDoseDate;
    private String veterinarian;
    private String notes;
}
