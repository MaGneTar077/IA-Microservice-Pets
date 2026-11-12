package com.myanimal.org.IA_service.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DeclaredData {

    private String legalName;
    private String nit;
    private String legalRepresentativeName;
    private String city;
}
