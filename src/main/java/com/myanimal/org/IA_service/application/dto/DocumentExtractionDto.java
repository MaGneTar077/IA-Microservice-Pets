package com.myanimal.org.IA_service.application.dto;

import java.util.List;

import com.myanimal.org.IA_service.domain.model.DocumentType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentExtractionDto {

    private DocumentType documentType;
    private String confianzaGeneral;
    private String fecha;
    private String veterinario;
    private String clinica;
    private String mascotaNombre;
    private String diagnostico;
    private String observaciones;
    private ExamenDataDto examen;
    private TratamientoDataDto tratamiento;
    private CirugiaDataDto cirugia;
    private List<String> camposIlegibles;
    private String textoOriginal;
}
