package com.myanimal.org.IA_service.domain.model;

import java.util.Arrays;
import java.util.Optional;

public enum DocumentType {
    RUT(true),
    CHAMBER_OF_COMMERCE(true),
    LEGAL_REP_ID(true),
    FACADE_PHOTO(false),
    PROFESSIONAL_CARD(false),
    ID_DOCUMENT(false),
    DEGREE(false);

    private final boolean supported;

    DocumentType(boolean supported) {
        this.supported = supported;
    }

    public boolean isSupported() {
        return supported;
    }

    public static Optional<DocumentType> fromCode(String code) {
        return Arrays.stream(values()).filter(type -> type.name().equals(code)).findFirst();
    }
}
