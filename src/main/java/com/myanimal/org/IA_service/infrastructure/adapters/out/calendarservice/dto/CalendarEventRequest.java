package com.myanimal.org.IA_service.infrastructure.adapters.out.calendarservice.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CalendarEventRequest {

    private UUID userId;
    private UUID petId;
    private String title;
    private String description;
    private String eventType;
    private Instant startDate;
    private Instant endDate;
    private String location;
    private Instant reminderAt;
    private boolean reminderEnabled;
}
