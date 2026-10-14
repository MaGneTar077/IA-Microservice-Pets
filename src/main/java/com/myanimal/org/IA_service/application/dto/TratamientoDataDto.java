package com.myanimal.org.IA_service.application.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TratamientoDataDto {

    private List<MedicamentoDto> medicamentos;
    private String indicaciones;
}
