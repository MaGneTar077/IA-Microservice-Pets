package com.myanimal.org.IA_service.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.model.CalendarEventRecord;
import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.out.CalendarServicePort;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;
import com.myanimal.org.IA_service.infrastructure.config.AppProperties;

@ExtendWith(MockitoExtension.class)
class AgendarCitaToolTest {

    @Mock
    private PetServicePort petServicePort;

    @Mock
    private CalendarServicePort calendarServicePort;

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);

    private AgendarCitaTool tool() {
        AppProperties appProperties = new AppProperties();
        appProperties.setTimezone("America/Bogota");
        return new AgendarCitaTool(petServicePort, calendarServicePort, appProperties, FIXED_CLOCK);
    }

    private Map<String, Object> baseArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("title", "Cita control Luna");
        return args;
    }

    @Test
    void requiereConfirmacion() {
        assertThat(tool().requiresConfirmation()).isTrue();
    }

    @Test
    void unaFechaConZSospechosaSeInterpretaComoHoraLocalDeBogotaNoComoUtcLiteral() {
        UserContext ctx = new UserContext(UUID.randomUUID(), "Ana", "jwt");
        when(calendarServicePort.createEvent(any(), eq("jwt"))).thenReturn(Map.of());
        Map<String, Object> args = baseArgs();
        args.put("startDate", "2026-09-29T10:00:00Z");

        tool().execute(args, ctx);

        ArgumentCaptor<CalendarEventRecord> captor = ArgumentCaptor.forClass(CalendarEventRecord.class);
        verify(calendarServicePort).createEvent(captor.capture(), eq("jwt"));
        // 10:00 hora Bogotá (UTC-5) = 15:00 UTC, no las 10:00 UTC que mandó el modelo.
        assertThat(captor.getValue().startDate()).isEqualTo(Instant.parse("2026-09-29T15:00:00Z"));
    }

    @Test
    void unOffsetExplicitoDistintoDeZSeRespetaTalCual() {
        UserContext ctx = new UserContext(UUID.randomUUID(), "Ana", "jwt");
        when(calendarServicePort.createEvent(any(), eq("jwt"))).thenReturn(Map.of());
        Map<String, Object> args = baseArgs();
        args.put("startDate", "2026-09-29T10:00:00-05:00");

        tool().execute(args, ctx);

        ArgumentCaptor<CalendarEventRecord> captor = ArgumentCaptor.forClass(CalendarEventRecord.class);
        verify(calendarServicePort).createEvent(captor.capture(), eq("jwt"));
        assertThat(captor.getValue().startDate()).isEqualTo(Instant.parse("2026-09-29T15:00:00Z"));
    }

    @Test
    void unaFechaSinZonaTambienSeInterpretaComoHoraLocalDeBogota() {
        UserContext ctx = new UserContext(UUID.randomUUID(), "Ana", "jwt");
        when(calendarServicePort.createEvent(any(), eq("jwt"))).thenReturn(Map.of());
        Map<String, Object> args = baseArgs();
        args.put("startDate", "2026-09-29T10:00:00");

        tool().execute(args, ctx);

        ArgumentCaptor<CalendarEventRecord> captor = ArgumentCaptor.forClass(CalendarEventRecord.class);
        verify(calendarServicePort).createEvent(captor.capture(), eq("jwt"));
        assertThat(captor.getValue().startDate()).isEqualTo(Instant.parse("2026-09-29T15:00:00Z"));
    }

    @Test
    void endDateAusenteEsStartDateMasUnaHora() {
        UserContext ctx = new UserContext(UUID.randomUUID(), "Ana", "jwt");
        when(calendarServicePort.createEvent(any(), eq("jwt"))).thenReturn(Map.of());
        Map<String, Object> args = baseArgs();
        args.put("startDate", "2026-09-29T10:00:00-05:00");

        tool().execute(args, ctx);

        ArgumentCaptor<CalendarEventRecord> captor = ArgumentCaptor.forClass(CalendarEventRecord.class);
        verify(calendarServicePort).createEvent(captor.capture(), eq("jwt"));
        CalendarEventRecord sent = captor.getValue();
        assertThat(sent.endDate()).isEqualTo(sent.startDate().plus(1, ChronoUnit.HOURS));
    }

    @Test
    void reminderAtAusenteEsStartDateMenosUnDiaConReminderEnabled() {
        UserContext ctx = new UserContext(UUID.randomUUID(), "Ana", "jwt");
        when(calendarServicePort.createEvent(any(), eq("jwt"))).thenReturn(Map.of());
        Map<String, Object> args = baseArgs();
        args.put("startDate", "2026-09-29T10:00:00-05:00");

        tool().execute(args, ctx);

        ArgumentCaptor<CalendarEventRecord> captor = ArgumentCaptor.forClass(CalendarEventRecord.class);
        verify(calendarServicePort).createEvent(captor.capture(), eq("jwt"));
        CalendarEventRecord sent = captor.getValue();
        assertThat(sent.reminderAt()).isEqualTo(sent.startDate().minus(1, ChronoUnit.DAYS));
        assertThat(sent.reminderEnabled()).isTrue();
    }

    @Test
    void unaFechaEnElPasadoLanzaToolExecutionExceptionYNoAgendaNada() {
        UserContext ctx = new UserContext(UUID.randomUUID(), "Ana", "jwt");
        Map<String, Object> args = baseArgs();
        args.put("startDate", "2020-01-01T10:00:00-05:00");

        assertThatThrownBy(() -> tool().execute(args, ctx)).isInstanceOf(ToolExecutionException.class);
        verify(calendarServicePort, never()).createEvent(any(), any());
    }

    @Test
    void sinEventTypeUsaVetAppointmentPorDefecto() {
        UserContext ctx = new UserContext(UUID.randomUUID(), "Ana", "jwt");
        when(calendarServicePort.createEvent(any(), eq("jwt"))).thenReturn(Map.of());
        Map<String, Object> args = baseArgs();
        args.put("startDate", "2026-09-29T10:00:00-05:00");

        tool().execute(args, ctx);

        ArgumentCaptor<CalendarEventRecord> captor = ArgumentCaptor.forClass(CalendarEventRecord.class);
        verify(calendarServicePort).createEvent(captor.capture(), eq("jwt"));
        assertThat(captor.getValue().eventType()).isEqualTo("VET_APPOINTMENT");
    }

    @Test
    void siHayPetIdVerificaPropiedadYRechazaSiNoEsDelUsuario() {
        UUID userId = UUID.randomUUID();
        UUID petId = UUID.randomUUID();
        UUID otroUsuario = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(petServicePort.getPet(petId, "jwt")).thenReturn(Pet.builder().id(petId).ownerId(otroUsuario).build());
        Map<String, Object> args = baseArgs();
        args.put("startDate", "2026-09-29T10:00:00-05:00");
        args.put("petId", petId.toString());

        assertThatThrownBy(() -> tool().execute(args, ctx)).isInstanceOf(ToolExecutionException.class);
        verify(calendarServicePort, never()).createEvent(any(), any());
    }

    @Test
    void sinPetIdNoVerificaPropiedadDeNingunaMascota() {
        UserContext ctx = new UserContext(UUID.randomUUID(), "Ana", "jwt");
        when(calendarServicePort.createEvent(any(), eq("jwt"))).thenReturn(Map.of());
        Map<String, Object> args = baseArgs();
        args.put("startDate", "2026-09-29T10:00:00-05:00");

        tool().execute(args, ctx);

        verify(petServicePort, never()).getPet(any(), any());
        ArgumentCaptor<CalendarEventRecord> captor = ArgumentCaptor.forClass(CalendarEventRecord.class);
        verify(calendarServicePort).createEvent(captor.capture(), eq("jwt"));
        assertThat(captor.getValue().petId()).isNull();
    }

    @Test
    void elUserIdSiempreVieneDelJwtNuncaDeLosArgs() {
        UUID userId = UUID.randomUUID();
        UUID userIdInventadoPorElModelo = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(calendarServicePort.createEvent(any(), eq("jwt"))).thenReturn(Map.of());
        Map<String, Object> args = baseArgs();
        args.put("startDate", "2026-09-29T10:00:00-05:00");
        args.put("userId", userIdInventadoPorElModelo.toString());

        tool().execute(args, ctx);

        ArgumentCaptor<CalendarEventRecord> captor = ArgumentCaptor.forClass(CalendarEventRecord.class);
        verify(calendarServicePort).createEvent(captor.capture(), eq("jwt"));
        assertThat(captor.getValue().userId()).isEqualTo(userId);
    }
}
