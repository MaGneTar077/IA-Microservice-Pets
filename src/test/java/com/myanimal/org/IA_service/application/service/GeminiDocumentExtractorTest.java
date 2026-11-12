package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysisRequest;
import com.myanimal.org.IA_service.application.dto.DocumentExtractionResult;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;

@ExtendWith(MockitoExtension.class)
class GeminiDocumentExtractorTest {

    private static final String VALID_JSON = """
            {
              "detectedType": "RUT",
              "legible": true,
              "extracted": {
                "nit": "900123456",
                "verificationDigit": "7",
                "legalName": "Clínica Patitas SAS",
                "legalRepresentativeName": "Nombre Apellido",
                "issueDate": "2026-10-20",
                "economicActivity": "7500"
              },
              "flags": [],
              "confidence": 0.9,
              "summary": "Documento legible y consistente."
            }
            """;

    @Mock
    private AiModelPort aiModelPort;

    private GeminiDocumentExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new GeminiDocumentExtractor(aiModelPort, "prompt de sistema", new ObjectMapper());
    }

    private DocumentAnalysisRequest request() {
        return DocumentAnalysisRequest.builder()
                .documentId(UUID.randomUUID())
                .veterinaryId(UUID.randomUUID())
                .documentType("RUT")
                .fileUrl("https://storage.test/signed-url")
                .mimeType("application/pdf")
                .sha256("abc123")
                .declared(DeclaredData.builder().clinicName("Clínica Patitas").nit("900123456-7").build())
                .build();
    }

    @Test
    void respuestaValidaSeParseaEnUnSoloIntento() {
        when(aiModelPort.generate(any())).thenReturn(
                AiResponse.builder().text(VALID_JSON).modelUsed("gemini-test").build());

        DocumentExtractionResult result = extractor.extract(request(), new byte[] { 1, 2, 3 });

        assertThat(result.getModelUsed()).isEqualTo("gemini-test");
        assertThat(result.getExtraction().getDetectedType()).isEqualTo("RUT");
        assertThat(result.getExtraction().getExtracted().getNit()).isEqualTo("900123456");
        verify(aiModelPort, times(1)).generate(any());
    }

    @Test
    void respuestaFueraDeEsquemaReintentaUnaVezYLuegoFunciona() {
        when(aiModelPort.generate(any()))
                .thenReturn(AiResponse.builder().text("esto no es json").modelUsed("gemini-test").build())
                .thenReturn(AiResponse.builder().text(VALID_JSON).modelUsed("gemini-test").build());

        DocumentExtractionResult result = extractor.extract(request(), new byte[] { 1, 2, 3 });

        assertThat(result.getExtraction().getDetectedType()).isEqualTo("RUT");
        verify(aiModelPort, times(2)).generate(any());
    }

    @Test
    void respuestaFueraDeEsquemaDosVecesLanzaAnalysisUnavailable() {
        when(aiModelPort.generate(any()))
                .thenReturn(AiResponse.builder().text("no json").modelUsed("gemini-test").build());

        assertThatThrownBy(() -> extractor.extract(request(), new byte[] { 1, 2, 3 }))
                .isInstanceOf(AnalysisUnavailableException.class);
        verify(aiModelPort, times(2)).generate(any());
    }

    @Test
    void elRequestIncluyeElEsquemaYElArchivoComoDatosBinarios() {
        when(aiModelPort.generate(any())).thenReturn(
                AiResponse.builder().text(VALID_JSON).modelUsed("gemini-test").build());
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);

        extractor.extract(request(), new byte[] { 9, 9, 9 });

        verify(aiModelPort).generate(captor.capture());
        AiRequest sent = captor.getValue();
        assertThat(sent.getResponseSchema()).isNotNull();
        assertThat(sent.getMessages()).hasSize(1);
        assertThat(sent.getMessages().get(0).getParts()).hasSize(2);
        assertThat(sent.getMessages().get(0).getParts().get(1).getData()).isEqualTo(new byte[] { 9, 9, 9 });
        assertThat(sent.getMessages().get(0).getParts().get(1).getMimeType()).isEqualTo("application/pdf");
    }
}
