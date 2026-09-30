package com.myanimal.org.IA_service.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.model.VaccineRecord;
import com.myanimal.org.IA_service.domain.ports.out.MedicalServicePort;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;
import com.myanimal.org.IA_service.infrastructure.adapters.out.medicalservice.MedicalServiceAdapter;

import reactor.core.publisher.Mono;

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
        assertThat(sent.userId()).isEqualTo(userId);
        assertThat(sent.petId()).isEqualTo(petId);
        assertThat(sent.name()).isEqualTo("Rabia");
        assertThat(sent.applicationDate()).isEqualTo(Instant.parse("2026-01-15T00:00:00Z"));
        assertThat(sent.lotNumber()).isEqualTo("LOT-2024-001");
        assertThat(sent.veterinarian()).isEqualTo("Dr. Pérez");
    }

    @Test
    void elPayloadSalienteDeRegistrarVacunaIncluyeElUserIdRealDelContexto() {
        // Reproduce el reporte real: medical-service persistió userId:"" pese a que
        // VaccineRecord ya lo traía. Esto pasa el UserContext por el mismo RegistrarVacunaTool
        // pero contra un MedicalServiceAdapter REAL (no mockeado) y verifica el JSON que
        // efectivamente sale a la red, no solo el VaccineRecord en memoria.
        UUID userId = UUID.randomUUID();
        UUID petId = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(petServicePort.getPet(petId, "jwt")).thenReturn(Pet.builder().id(petId).ownerId(userId).build());

        AtomicReference<String> capturedBody = new AtomicReference<>();
        Queue<Object> responses = new ConcurrentLinkedQueue<>(List.of(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body("{\"id\":\"abc\"}")
                        .build()));
        WebClient webClient = WebClient.builder()
                .baseUrl("http://medical-service.test")
                .exchangeFunction(request -> {
                    MockClientHttpRequest httpRequest = new MockClientHttpRequest(request.method(), request.url());
                    request.writeTo(httpRequest, ExchangeStrategies.withDefaults()).block();
                    capturedBody.set(httpRequest.getBodyAsString().block());
                    return Mono.just((ClientResponse) responses.poll());
                })
                .build();
        RegistrarVacunaTool toolConAdapterReal = new RegistrarVacunaTool(petServicePort,
                new MedicalServiceAdapter(webClient));

        Map<String, Object> args = Map.of(
                "petId", petId.toString(), "name", "Rabia", "applicationDate", "2026-01-15T00:00:00Z");

        toolConAdapterReal.execute(args, ctx);

        assertThat(capturedBody.get()).contains("\"userId\":\"" + userId + "\"");
    }

    @Test
    void laDeclaracionNoExponeUserId() {
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) tool().declaration().get("parameters");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");

        assertThat(properties).doesNotContainKeys("userId", "ownerId");
    }

    @Test
    void elUserIdSiempreVieneDelJwtAunqueElModeloMandeUnoEnLosArgs() {
        UUID userId = UUID.randomUUID();
        UUID userIdInventadoPorElModelo = UUID.randomUUID();
        UUID petId = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(petServicePort.getPet(petId, "jwt"))
                .thenReturn(Pet.builder().id(petId).ownerId(userId).name("Luna").build());
        when(medicalServicePort.registerVaccine(any(), eq("jwt"))).thenReturn(Map.of());

        Map<String, Object> args = Map.of(
                "petId", petId.toString(),
                "name", "Rabia",
                "applicationDate", "2026-01-15T00:00:00Z",
                "userId", userIdInventadoPorElModelo.toString());

        tool().execute(args, ctx);

        ArgumentCaptor<VaccineRecord> captor = ArgumentCaptor.forClass(VaccineRecord.class);
        verify(medicalServicePort).registerVaccine(captor.capture(), eq("jwt"));
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        assertThat(captor.getValue().userId()).isNotEqualTo(userIdInventadoPorElModelo);
    }

    @Test
    void aceptaUnaFechaConOffsetExplicitoSinLanzarYSinReinterpretarLaZona() {
        UUID userId = UUID.randomUUID();
        UUID petId = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(petServicePort.getPet(petId, "jwt"))
                .thenReturn(Pet.builder().id(petId).ownerId(userId).name("Luna").build());
        when(medicalServicePort.registerVaccine(any(), eq("jwt"))).thenReturn(Map.of());

        // Dos formatos vistos en producción para la misma fecha: con offset explícito y como
        // fecha de calendario sin hora. Ninguno debe lanzar ni desplazar la hora.
        Map<String, Object> args = Map.of(
                "petId", petId.toString(), "name", "Rabia", "applicationDate", "2026-01-15T00:00:00-05:00");

        tool().execute(args, ctx);

        ArgumentCaptor<VaccineRecord> captor = ArgumentCaptor.forClass(VaccineRecord.class);
        verify(medicalServicePort).registerVaccine(captor.capture(), eq("jwt"));
        assertThat(captor.getValue().applicationDate()).isEqualTo(Instant.parse("2026-01-15T05:00:00Z"));
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
