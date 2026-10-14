package com.myanimal.org.IA_service.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CirugiaDataDto {

    private String procedimiento;
    private String anestesia;
    private String complicaciones;
    private String cuidadosPostoperatorios;
    private String proximoControl;
}
