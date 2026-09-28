package com.myanimal.org.IA_service.domain.model;

import java.time.LocalDate;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class Pet {

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
