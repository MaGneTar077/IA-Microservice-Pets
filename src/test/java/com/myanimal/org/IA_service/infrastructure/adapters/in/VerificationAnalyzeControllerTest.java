package com.myanimal.org.IA_service.infrastructure.adapters.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.myanimal.org.IA_service.application.dto.VerificationAnalyzeResponse;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisValidationException;
import com.myanimal.org.IA_service.domain.ports.in.VerificationAnalyzeUseCase;
import com.myanimal.org.IA_service.infrastructure.config.SecurityConfig;

// El filtro de API key también corre en este slice porque @WebMvcTest incluye cualquier bean
// Filter. JwtAuthenticationFilter se salta /internal/** en shouldNotFilter, pero igual
// necesita un JwtDecoder mockeable.
@WebMvcTest(VerificationAnalyzeController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class VerificationAnalyzeControllerTest {

    private static final String PATH = "/internal/verification/analyze";
    private static final String VALID_BODY = """
            {
              "requestId": "req-1",
              "subjectType": "CLINIC",
              "documents": [
                { "documentType": "RUT", "signedUrl": "https://storage.test/signed-url" }
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerificationAnalyzeUseCase verificationAnalyzeUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void sinApiKeyDevuelve401() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void conApiKeyCorrectaDelegaAlUseCase() throws Exception {
        when(verificationAnalyzeUseCase.analyze(any())).thenReturn(
                VerificationAnalyzeResponse.builder().requestId("req-1").recommendation("READY_FOR_HUMAN_REVIEW")
                        .riskLevel("LOW").build());

        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.recommendation").value("READY_FOR_HUMAN_REVIEW"))
                .andExpect(jsonPath("$.riskLevel").value("LOW"));
    }

    @Test
    void cuerpoInvalidoDevuelve400VALIDATION_ERROR() throws Exception {
        when(verificationAnalyzeUseCase.analyze(any()))
                .thenThrow(new DocumentAnalysisValidationException("documents no puede estar vacío."));

        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void siTodosLosDocumentosFallanDevuelve503() throws Exception {
        when(verificationAnalyzeUseCase.analyze(any()))
                .thenThrow(new AnalysisUnavailableException("No se pudo procesar ningún documento."));

        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
    }
}
