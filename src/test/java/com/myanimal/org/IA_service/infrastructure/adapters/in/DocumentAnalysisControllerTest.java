package com.myanimal.org.IA_service.infrastructure.adapters.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.myanimal.org.IA_service.application.dto.DocumentAnalysisResponse;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisValidationException;
import com.myanimal.org.IA_service.domain.exception.FileTooLargeException;
import com.myanimal.org.IA_service.domain.exception.FileUrlNotAllowedException;
import com.myanimal.org.IA_service.domain.exception.UnsupportedFileTypeException;
import com.myanimal.org.IA_service.domain.ports.in.DocumentAnalysisUseCase;
import com.myanimal.org.IA_service.infrastructure.config.SecurityConfig;

// El filtro de API key (ver "trampas conocidas" en CLAUDE.md para el equivalente con JWT) también
// corre en este slice porque @WebMvcTest incluye cualquier bean Filter. JwtAuthenticationFilter
// se salta /internal/** en shouldNotFilter, pero igual necesita un JwtDecoder mockeable.
@WebMvcTest(DocumentAnalysisController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class DocumentAnalysisControllerTest {

    private static final String PATH = "/internal/ai/document-analysis";
    private static final String VALID_BODY = """
            {
              "documentId": "11111111-1111-1111-1111-111111111111",
              "veterinaryId": "22222222-2222-2222-2222-222222222222",
              "documentType": "RUT",
              "fileUrl": "https://storage.test/signed-url",
              "mimeType": "application/pdf",
              "sha256": "abc123",
              "declared": { "clinicName": "Clínica Patitas", "nit": "900123456-7" }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentAnalysisUseCase documentAnalysisUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void sinApiKeyDevuelve401ConFormatoDeErrorInterno() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void conApiKeyIncorrectaDevuelve401() throws Exception {
        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "otra-llave")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conApiKeyCorrectaYCuerpoValidoDelegaAlUseCase() throws Exception {
        UUID documentId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(documentAnalysisUseCase.analyze(any())).thenReturn(
                DocumentAnalysisResponse.builder().documentId(documentId).supported(false).build());

        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supported").value(false));
    }

    @Test
    void cuerpoInvalidoDevuelve400VALIDATION_ERROR() throws Exception {
        when(documentAnalysisUseCase.analyze(any()))
                .thenThrow(new DocumentAnalysisValidationException("documentId es obligatorio."));

        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void cuerpoMalformadoDevuelve400VALIDATION_ERROR() throws Exception {
        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{ no-es-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void cuandoElAnalisisNoEstaDisponibleDevuelve503() throws Exception {
        when(documentAnalysisUseCase.analyze(any()))
                .thenThrow(new AnalysisUnavailableException("todavía no implementado"));

        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void mimeTypeNoPermitidoDevuelve400UNSUPPORTED_MIME_TYPE() throws Exception {
        when(documentAnalysisUseCase.analyze(any()))
                .thenThrow(new UnsupportedFileTypeException("El tipo de archivo application/zip no está permitido."));

        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MIME_TYPE"));
    }

    @Test
    void hostNoPermitidoDevuelve400FILE_URL_NOT_ALLOWED() throws Exception {
        when(documentAnalysisUseCase.analyze(any()))
                .thenThrow(new FileUrlNotAllowedException("El host de fileUrl no está permitido."));

        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_URL_NOT_ALLOWED"));
    }

    @Test
    void archivoDemasiadoGrandeDevuelve413FILE_TOO_LARGE() throws Exception {
        when(documentAnalysisUseCase.analyze(any()))
                .thenThrow(new FileTooLargeException("El archivo supera el tamaño máximo permitido."));

        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }
}
