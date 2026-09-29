package com.myanimal.org.IA_service.domain.model;

import java.time.Instant;
import java.util.UUID;

public record VaccineRecord(UUID petId, String name, String lotNumber, Instant applicationDate,
        Instant nextDoseDate, String veterinarian, String notes) {
}
