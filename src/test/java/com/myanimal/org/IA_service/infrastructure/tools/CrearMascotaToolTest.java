package com.myanimal.org.IA_service.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;

@ExtendWith(MockitoExtension.class)
class CrearMascotaToolTest {

    @Mock
    private PetServicePort petServicePort;

    private CrearMascotaTool tool() {
        return new CrearMascotaTool(petServicePort);
    }

    @Test
    void requiereConfirmacion() {
        assertThat(tool().requiresConfirmation()).isTrue();
    }

    @Test
    void laDeclaracionNoExponeOwnerIdNiUserId() {
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) tool().declaration().get("parameters");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");

        assertThat(properties).doesNotContainKeys("ownerId", "userId");
    }

    @Test
    void elOwnerIdSiempreVieneDelJwtAunqueElModeloMandeUnoEnLosArgs() {
        UUID userId = UUID.randomUUID();
        UUID ownerIdInventadoPorElModelo = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(petServicePort.createPet(any(), eq("jwt"))).thenReturn(Pet.builder()
                .id(UUID.randomUUID()).ownerId(userId).name("Luna").species("Perro").build());

        Map<String, Object> args = Map.of(
                "name", "Luna",
                "species", "Perro",
                "ownerId", ownerIdInventadoPorElModelo.toString(),
                "userId", ownerIdInventadoPorElModelo.toString());

        tool().execute(args, ctx);

        ArgumentCaptor<Pet> petCaptor = ArgumentCaptor.forClass(Pet.class);
        verify(petServicePort).createPet(petCaptor.capture(), eq("jwt"));
        assertThat(petCaptor.getValue().getOwnerId()).isEqualTo(userId);
        assertThat(petCaptor.getValue().getOwnerId()).isNotEqualTo(ownerIdInventadoPorElModelo);
    }

    @Test
    void mapeaTodosLosCamposOpcionales() {
        UUID userId = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt");
        when(petServicePort.createPet(any(), eq("jwt"))).thenReturn(Pet.builder()
                .id(UUID.randomUUID()).ownerId(userId).name("Luna").species("Perro").breed("Labrador").build());

        Map<String, Object> args = Map.of(
                "name", "Luna",
                "species", "Perro",
                "breed", "Labrador",
                "sex", "FEMALE",
                "birthDate", "2022-01-15",
                "height", 45.5,
                "weight", 12.3);

        Object result = tool().execute(args, ctx);

        ArgumentCaptor<Pet> petCaptor = ArgumentCaptor.forClass(Pet.class);
        verify(petServicePort).createPet(petCaptor.capture(), eq("jwt"));
        Pet sent = petCaptor.getValue();
        assertThat(sent.getBreed()).isEqualTo("Labrador");
        assertThat(sent.getSex()).isEqualTo("FEMALE");
        assertThat(sent.getBirthDate()).isEqualTo(LocalDate.of(2022, 1, 15));
        assertThat(sent.getHeight()).isEqualTo(45.5);
        assertThat(sent.getWeight()).isEqualTo(12.3);
        assertThat(result).isInstanceOf(Map.class);
    }

    @Test
    void describeGeneraUnTextoLegibleConEspecieYRaza() {
        String description = tool().describe(Map.of("name", "Luna", "species", "Perro", "breed", "Labrador"));

        assertThat(description).isEqualTo("Registrar a Luna (Perro, Labrador)");
    }
}
