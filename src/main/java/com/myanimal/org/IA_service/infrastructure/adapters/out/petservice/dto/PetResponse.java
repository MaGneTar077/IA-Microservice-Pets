package com.myanimal.org.IA_service.infrastructure.adapters.out.petservice.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PetResponse {

    private UUID id;
    private UUID ownerId;
    private String name;
    private String species;
    private String breed;
    private String sex;
    private LocalDate birthDate;
    private Double height;
    private Double weight;
}
