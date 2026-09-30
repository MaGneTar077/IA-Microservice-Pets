package com.myanimal.org.IA_service.infrastructure.adapters.out.calendarservice;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.exception.UpstreamSessionExpiredException;
import com.myanimal.org.IA_service.domain.model.CalendarEventRecord;
import com.myanimal.org.IA_service.domain.ports.out.CalendarServicePort;
import com.myanimal.org.IA_service.infrastructure.adapters.out.IsoUtcDateFormatter;
import com.myanimal.org.IA_service.infrastructure.adapters.out.calendarservice.dto.CalendarEventRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CalendarServiceAdapter implements CalendarServicePort {

    private final WebClient calendarServiceWebClient;

    public CalendarServiceAdapter(@Qualifier("calendarServiceWebClient") WebClient calendarServiceWebClient) {
        this.calendarServiceWebClient = calendarServiceWebClient;
    }

    @Override
    public Map<String, Object> createEvent(CalendarEventRecord record, String rawJwt) {
        CalendarEventRequest request = CalendarEventRequest.builder()
                .userId(record.userId())
                .petId(record.petId())
                .title(record.title())
                .description(record.description())
                .eventType(record.eventType())
                .startDate(IsoUtcDateFormatter.format(record.startDate()))
                .endDate(IsoUtcDateFormatter.format(record.endDate()))
                .location(record.location())
                .reminderAt(IsoUtcDateFormatter.format(record.reminderAt()))
                .reminderEnabled(record.reminderEnabled())
                .build();
        try {
            return calendarServiceWebClient.post()
                    .uri("/api/calendar-events")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawJwt)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block();
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                throw new UpstreamSessionExpiredException("La sesión expiró al llamar a calendar-service.", ex);
            }
            log.warn("calendar-service respondió {} al agendar una cita: {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            throw new ToolExecutionException("No se pudo agendar la cita. Revisa los datos e intenta de nuevo.");
        }
    }
}
