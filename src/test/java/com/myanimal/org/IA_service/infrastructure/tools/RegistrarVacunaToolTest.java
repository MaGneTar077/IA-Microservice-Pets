package com.myanimal.org.IA_service.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.model.VaccineRecord;
import com.myanimal.org.IA_service.domain.ports.out.MedicalServicePort;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;

@ExtendWith(MockitoExtension.class)
class RegistrarVacunaToolTest {

    @Mock
    private PetServicePort petServicePort;

    @Mock
    private MedicalServicePort medicalServicePort;

    private RegistrarVacunaTool tool() {
        return new RegistrarVacunaTool(petServicePort, medicalServicePort);
    }

    @Test
    void requiereConfirmacion() {
        assertThat(tool().requiresConfirmation()).isTrue();
    }

    @Test
    void siLaMascotaNoEsDelUsuarioNuncaLlamaAMedicalService() {
        UUID userId = UUID.randomUUID();
        UUID petId = UUID.randomUUID();
        UUID otroUsuario = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(petServicePort.getPet(petId, "jwt"))
                .thenReturn(Pet.builder().id(petId).ownerId(otroUsuario).name("Rex").build());

        Map<String, Object> args = Map.of(
                "petId", petId.toString(), "name", "Rabia", "applicationDate", "2026-01-15T00:00:00Z");

        assertThatThrownBy(() -> tool().execute(args, ctx)).isInstanceOf(ToolExecutionException.class);
        verify(medicalServicePort, never()).registerVaccine(any(), any());
    }

    @Test
    void siLaMascotaEsDelUsuarioRegistraLaVacuna() {
        UUID userId = UUID.randomUUID();
        UUID petId = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(petServicePort.getPet(petId, "jwt"))
                .thenReturn(Pet.builder().id(petId).ownerId(userId).name("Luna").build());
        when(medicalServicePort.registerVaccine(any(), eq("jwt"))).thenReturn(Map.of("id", UUID.randomUUID()));

        Map<String, Object> args = Map.of(
                "petId", petId.toString(),
                "name", "Rabia",
                "applicationDate", "2026-01-15T00:00:00Z",
                "lotNumber", "LOT-2024-001",
                "veterinarian", "Dr. Pérez");

        tool().execute(args, ctx);

        ArgumentCaptor<VaccineRecord> captor = ArgumentCaptor.forClass(VaccineRecord.class);
        verify(medicalServicePort).registerVaccine(captor.capture(), eq("jwt"));
        VaccineRecord sent = captor.getValue();
        assertThat(sent.petId()).isEqualTo(petId);
        assertThat(sent.name()).isEqualTo("Rabia");
        assertThat(sent.applicationDate()).isEqualTo(Instant.parse("2026-01-15T00:00:00Z"));
        assertThat(sent.lotNumber()).isEqualTo("LOT-2024-001");
        assertThat(sent.veterinarian()).isEqualTo("Dr. Pérez");
    }

    @Test
    void aceptaUnaFechaSoloDeCalendarioSinHora() {
        UUID userId = UUID.randomUUID();
        UUID petId = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(petServicePort.getPet(petId, "jwt"))
                .thenReturn(Pet.builder().id(petId).ownerId(userId).name("Luna").build());
        when(medicalServicePort.registerVaccine(any(), eq("jwt"))).thenReturn(Map.of());

        Map<String, Object> args = Map.of(
                "petId", petId.toString(), "name", "Rabia", "applicationDate", "2026-01-15");

        tool().execute(args, ctx);

        ArgumentCaptor<VaccineRecord> captor = ArgumentCaptor.forClass(VaccineRecord.class);
        verify(medicalServicePort).registerVaccine(captor.capture(), eq("jwt"));
        assertThat(captor.getValue().applicationDate()).isEqualTo(Instant.parse("2026-01-15T00:00:00Z"));
    }

    @Test
    void unPetIdQueNoEsUnUuidValidoLanzaToolExecutionExceptionSinLlamarANadie() {
        UserContext ctx = new UserContext(UUID.randomUUID(), "Ana", "jwt");
        Map<String, Object> args = Map.of(
                "petId", "no-es-un-uuid", "name", "Rabia", "applicationDate", "2026-01-15");

        assertThatThrownBy(() -> tool().execute(args, ctx)).isInstanceOf(ToolExecutionException.class);
        verify(petServicePort, never()).getPet(any(), any());
        verify(medicalServicePort, never()).registerVaccine(any(), any());
    }

    @Test
    void validateVerificaPropiedadSinLlamarAMedicalService() {
        UUID userId = UUID.randomUUID();
        UUID petId = UUID.randomUUID();
        UUID otroUsuario = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(petServicePort.getPet(petId, "jwt")).thenReturn(Pet.builder().id(petId).ownerId(otroUsuario).build());

        Map<String, Object> args = Map.of(
                "petId", petId.toString(), "name", "Rabia", "applicationDate", "2026-01-15T00:00:00Z");

        assertThatThrownBy(() -> tool().validate(args, ctx)).isInstanceOf(ToolExecutionException.class);
        verify(medicalServicePort, never()).registerVaccine(any(), any());
    }

    @Test
    void validateConMascotaPropiaDevuelveLosMismosArgs() {
        UUID userId = UUID.randomUUID();
        UUID petId = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(petServicePort.getPet(petId, "jwt")).thenReturn(Pet.builder().id(petId).ownerId(userId).build());

        Map<String, Object> args = Map.of(
                "petId", petId.toString(), "name", "Rabia", "applicationDate", "2026-01-15T00:00:00Z");

        Map<String, Object> validated = tool().validate(args, ctx);

        assertThat(validated).isEqualTo(args);
        verify(medicalServicePort, never()).registerVaccine(any(), any());
    }
}
