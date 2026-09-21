package com.myanimal.org.IA_service.infrastructure.adapters.in.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatRequestDto {

    @NotBlank(message = "El mensaje no puede estar vacío.")
    @Size(max = 4000, message = "El mensaje no puede superar los 4000 caracteres.")
    private String message;
}
