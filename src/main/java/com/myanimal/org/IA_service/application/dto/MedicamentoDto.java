package com.myanimal.org.IA_service.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MedicamentoDto {

    private String nombre;
    private String dosis;
    private String frecuencia;
    private String duracion;
    private String via;
}
