package com.myanimal.org.IA_service.domain.ports.out;

import java.util.Map;

import com.myanimal.org.IA_service.domain.model.CalendarEventRecord;

public interface CalendarServicePort {

    Map<String, Object> createEvent(CalendarEventRecord record, String rawJwt);
}
