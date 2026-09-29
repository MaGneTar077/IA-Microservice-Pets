package com.myanimal.org.IA_service.domain.model;

import java.time.Instant;
import java.util.UUID;

public record CalendarEventRecord(UUID userId, UUID petId, String title, String description, String eventType,
        Instant startDate, Instant endDate, String location, Instant reminderAt, boolean reminderEnabled) {
}
