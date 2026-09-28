package com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Misma forma para el functionCall que viaja en la respuesta de Gemini y el que se
 * reenvía como parte del turno del modelo al continuar la conversación. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiFunctionCall {

    private String id;
    private String name;
    private Map<String, Object> args;
}
