package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myanimal.org.IA_service.application.dto.DocumentExtractionDto;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisFailedException;
import com.myanimal.org.IA_service.domain.exception.AiResponseTruncatedException;
import com.myanimal.org.IA_service.domain.exception.PetNotFoundException;
import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.model.DocumentType;
import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;

@ExtendWith(MockitoExtension.class)
class DocumentAnalysisServiceTest {

    @Mock
    private AiModelPort aiModelPort;

    @Mock
    private PetServicePort petServicePort;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Object> schema = Map.of("type", "object");
    private final String promptText = "extrae";

    private DocumentAnalysisService service() {
        return new DocumentAnalysisService(aiModelPort, petServicePort, objectMapper, schema, promptText);
    }

    private UserContext userContext() {
        return new UserContext(UUID.randomUUID(), "Ana", "fake.jwt");
    }

    private UploadedFile pdfFile() {
        return new UploadedFile(new byte[] { 1, 2, 3 }, "application/pdf");
    }

    private AiResponse responseWithText(String text) {
        return AiResponse.builder().text(text).modelUsed("gemini-test").build();
    }

    @Test
    void extraeUnExamenDeSangreConSusResultados() {
        String json = """
                {
                  "documentType": "EXAM",
                  "confianzaGeneral": "ALTA",
                  "examen": {
                    "tipoExamen": "Hemograma",
                    "resultados": [
                      { "parametro": "Hematocrito", "valor": "45", "unidad": "%", "fueraDeRango": false }
                    ]
                  }
                }
                """;
        when(aiModelPort.generate(any())).thenReturn(responseWithText(json));

        DocumentExtractionDto result = service().analyze(userContext(), pdfFile(), null);

        assertThat(result.getDocumentType()).isEqualTo(DocumentType.EXAM);
        assertThat(result.getExamen().getResultados()).hasSize(1);
        assertThat(result.getExamen().getResultados().get(0).getParametro()).isEqualTo("Hematocrito");
    }

    @Test
    void extraeUnTratamientoConMedicamentos() {
        String json = """
                {
                  "documentType": "TREATMENT",
                  "confianzaGeneral": "ALTA",
                  "tratamiento": {
                    "medicamentos": [
                      { "nombre": "Apoquel", "dosis": "16 mg", "frecuencia": "cada 12 horas" }
                    ],
                    "indicaciones": "Con comida"
                  }
                }
                """;
        when(aiModelPort.generate(any())).thenReturn(responseWithText(json));

        DocumentExtractionDto result = service().analyze(userContext(), pdfFile(), null);

        assertThat(result.getDocumentType()).isEqualTo(DocumentType.TREATMENT);
        assertThat(result.getTratamiento().getMedicamentos().get(0).getNombre()).isEqualTo("Apoquel");
    }

    @Test
    void extraeUnaCirugiaConSusCuidados() {
        String json = """
                {
                  "documentType": "SURGERY",
                  "confianzaGeneral": "MEDIA",
                  "cirugia": {
                    "procedimiento": "Esterilización",
                    "cuidadosPostoperatorios": "Reposo 7 días"
                  }
                }
                """;
        when(aiModelPort.generate(any())).thenReturn(responseWithText(json));

        DocumentExtractionDto result = service().analyze(userContext(), pdfFile(), null);

        assertThat(result.getDocumentType()).isEqualTo(DocumentType.SURGERY);
        assertThat(result.getCirugia().getProcedimiento()).isEqualTo("Esterilización");
    }

    @Test
    void documentoNoVeterinarioDevuelveUnknownSinLanzarExcepcion() {
        String json = """
                { "documentType": "UNKNOWN", "confianzaGeneral": "BAJA" }
                """;
        when(aiModelPort.generate(any())).thenReturn(responseWithText(json));

        DocumentExtractionDto result = service().analyze(userContext(), pdfFile(), null);

        assertThat(result.getDocumentType()).isEqualTo(DocumentType.UNKNOWN);
    }

    @Test
    void stringLiteralNullSeConvierteEnNullRealEnCamposAnidados() {
        String json = """
                {
                  "documentType": "TREATMENT",
                  "confianzaGeneral": "ALTA",
                  "diagnostico": "null",
                  "tratamiento": {
                    "indicaciones": "null",
                    "medicamentos": [
                      { "nombre": "Apoquel", "dosis": "null", "frecuencia": "cada 12 horas" }
                    ]
                  },
                  "camposIlegibles": ["null"]
                }
                """;
        when(aiModelPort.generate(any())).thenReturn(responseWithText(json));

        DocumentExtractionDto result = service().analyze(userContext(), pdfFile(), null);

        assertThat(result.getDiagnostico()).isNull();
        assertThat(result.getTratamiento().getIndicaciones()).isNull();
        assertThat(result.getTratamiento().getMedicamentos().get(0).getDosis()).isNull();
        assertThat(result.getTratamiento().getMedicamentos().get(0).getFrecuencia()).isEqualTo("cada 12 horas");
        assertThat(result.getCamposIlegibles()).containsExactly((String) null);
    }

    @Test
    void jsonInvalidoReintentaUnaVezYLuegoTieneExito() {
        String validJson = """
                { "documentType": "UNKNOWN", "confianzaGeneral": "BAJA" }
                """;
        when(aiModelPort.generate(any()))
                .thenReturn(responseWithText("esto no es JSON"))
                .thenReturn(responseWithText(validJson));

        DocumentExtractionDto result = service().analyze(userContext(), pdfFile(), null);

        assertThat(result.getDocumentType()).isEqualTo(DocumentType.UNKNOWN);
        verify(aiModelPort, times(2)).generate(any());
    }

    @Test
    void jsonInvalidoTrasElReintentoLanza502() {
        when(aiModelPort.generate(any())).thenReturn(responseWithText("esto no es JSON"));

        assertThatThrownBy(() -> service().analyze(userContext(), pdfFile(), null))
                .isInstanceOf(DocumentAnalysisFailedException.class);
        verify(aiModelPort, times(2)).generate(any());
    }

    @Test
    void respuestaTruncadaNoSeReintentaYSePropaga() {
        when(aiModelPort.generate(any()))
                .thenThrow(new AiResponseTruncatedException("La respuesta del modelo se cortó por límite de tokens."));

        assertThatThrownBy(() -> service().analyze(userContext(), pdfFile(), null))
                .isInstanceOf(AiResponseTruncatedException.class);
        // El reintento de este service existe para un JSON que no parsea, no para una
        // respuesta truncada: con el mismo prompt y el mismo límite se cortaría igual.
        verify(aiModelPort, times(1)).generate(any());
    }

    @Test
    void petIdAjenoNoLlegaALlamarAGemini() {
        UserContext ctx = userContext();
        UUID petId = UUID.randomUUID();
        when(petServicePort.getPet(petId, ctx.rawJwt()))
                .thenReturn(Pet.builder().id(petId).ownerId(UUID.randomUUID()).build());

        assertThatThrownBy(() -> service().analyze(ctx, pdfFile(), petId))
                .isInstanceOf(PetNotFoundException.class);
        verify(aiModelPort, never()).generate(any());
    }

    @Test
    void petIdInexistenteNoLlegaALlamarAGemini() {
        UserContext ctx = userContext();
        UUID petId = UUID.randomUUID();
        when(petServicePort.getPet(petId, ctx.rawJwt()))
                .thenThrow(new ToolExecutionException("No se encontró la mascota."));

        assertThatThrownBy(() -> service().analyze(ctx, pdfFile(), petId))
                .isInstanceOf(PetNotFoundException.class);
        verify(aiModelPort, never()).generate(any());
    }
}
