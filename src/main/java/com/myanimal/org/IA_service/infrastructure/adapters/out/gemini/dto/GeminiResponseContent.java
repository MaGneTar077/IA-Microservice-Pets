package com.myanimal.org.IA_service.infrastructure.adapters.out.gemini.dto;

import java.util.List;

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
public class GeminiResponseContent {

    private String role;
    private List<GeminiResponsePart> parts;
}
