package com.myanimal.org.IA_service.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;

@ExtendWith(MockitoExtension.class)
class ListarMascotasToolTest {

    @Mock
    private PetServicePort petServicePort;

    @Test
    void noRequiereConfirmacion() {
        assertThat(new ListarMascotasTool(petServicePort).requiresConfirmation()).isFalse();
    }

    @Test
    void laDeclaracionNoExponeOwnerIdNiUserId() {
        Map<String, Object> declaration = new ListarMascotasTool(petServicePort).declaration();

        assertThat(declaration.get("name")).isEqualTo("listar_mascotas");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) declaration.get("parameters");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        assertThat(properties).doesNotContainKeys("ownerId", "userId");
        assertThat(properties).isEmpty();
    }

    @Test
    void execRecortaLosCamposAlSubconjuntoNecesarioParaElModelo() {
        UUID userId = UUID.randomUUID();
        UserContext ctx = new UserContext(userId, "Ana", "jwt-real");
        Pet pet = Pet.builder()
                .id(UUID.randomUUID())
                .ownerId(userId)
                .name("Luna")
                .species("Perro")
                .breed("Labrador")
                .sex("FEMALE")
                .birthDate(LocalDate.of(2022, 1, 15))
                .height(45.5)
                .weight(12.3)
                .build();
        when(petServicePort.listPets(userId, "jwt-real")).thenReturn(List.of(pet));

        Object result = new ListarMascotasTool(petServicePort).execute(Map.of(), ctx);

        assertThat(result).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) result;
        assertThat(summaries).hasSize(1);
        Map<String, Object> summary = summaries.get(0);
        assertThat(summary.keySet()).containsExactlyInAnyOrder(
                "id", "name", "species", "breed", "sex", "birthDate");
        assertThat(summary.get("name")).isEqualTo("Luna");
    }
}
