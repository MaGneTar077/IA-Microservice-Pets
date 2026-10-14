package com.myanimal.org.IA_service.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ResultadoExamenDto {

    private String parametro;
    private String valor;
    private String unidad;
    private String rangoReferencia;
    private Boolean fueraDeRango;
}
