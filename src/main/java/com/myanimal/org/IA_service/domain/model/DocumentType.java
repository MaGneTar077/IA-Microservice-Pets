package com.myanimal.org.IA_service.domain.model;

import java.util.Arrays;
import java.util.Optional;

public enum DocumentType {
    RUT,
    CAMARA_COMERCIO;

    public static Optional<DocumentType> fromCode(String code) {
        return Arrays.stream(values()).filter(type -> type.name().equals(code)).findFirst();
    }
}
