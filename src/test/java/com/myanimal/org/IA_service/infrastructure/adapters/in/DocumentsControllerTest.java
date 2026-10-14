package com.myanimal.org.IA_service.infrastructure.adapters.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.myanimal.org.IA_service.application.dto.DocumentExtractionDto;
import com.myanimal.org.IA_service.domain.exception.PetNotFoundException;
import com.myanimal.org.IA_service.domain.model.DocumentType;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.DocumentAnalysisUseCase;
import com.myanimal.org.IA_service.infrastructure.adapters.in.attachment.DocumentFileValidator;
import com.myanimal.org.IA_service.infrastructure.config.DocumentsProperties;
import com.myanimal.org.IA_service.infrastructure.config.SecurityConfig;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

/**
 * Usa el DocumentFileValidator y el DocumentsProperties REALES (no mockeados), igual que
 * AiChatMultipartControllerTest con AttachmentProcessor: así se ejercita de verdad la
 * detección de MIME por magic bytes y el límite de tamaño propio de documents.*.
 */
@WebMvcTest(DocumentsController.class)
@Import({ SecurityConfig.class, DocumentFileValidator.class, DocumentsProperties.class })
@ActiveProfiles("test")
class DocumentsControllerTest {

    private static final byte[] PNG_HEADER = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentAnalysisUseCase documentAnalysisUseCase;

    @MockitoBean
    private UserContextProvider userContextProvider;

    // Etapa 3.5: la validación es contra un JWKS, no un secreto compartido — se mockea
    // JwtDecoder para pasar el filtro sin firmar un JWT real.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID userId = UUID.randomUUID();
    private final String bearerToken = "Bearer fake-token";

    @BeforeEach
    void setUp() {
        when(userContextProvider.current()).thenReturn(new UserContext(userId, "Ana", "fake.jwt.token"));

        Jwt fakeJwt = Jwt.withTokenValue("fake-token")
                .header("alg", "RS256")
                .claim("id", userId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(fakeJwt);
    }

    private byte[] pdfBytes() {
        return "%PDF-1.4 contenido de prueba".getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] pngOfSize(int size) {
        byte[] content = new byte[size];
        System.arraycopy(PNG_HEADER, 0, content, 0, PNG_HEADER.length);
        return content;
    }

    @Test
    void pdfDevuelve200() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "examen.pdf", "application/pdf", pdfBytes());
        when(documentAnalysisUseCase.analyze(any(), any(), isNull())).thenReturn(DocumentExtractionDto.builder()
                .documentType(DocumentType.EXAM)
                .confianzaGeneral("ALTA")
                .build());

        mockMvc.perform(multipart("/api/ai/documents/analyze")
                        .file(file)
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("EXAM"));
    }

    @Test
    void imagenDevuelve200() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "receta.png", "image/png", pngOfSize(100));
        when(documentAnalysisUseCase.analyze(any(), any(), isNull())).thenReturn(DocumentExtractionDto.builder()
                .documentType(DocumentType.TREATMENT)
                .confianzaGeneral("ALTA")
                .build());

        mockMvc.perform(multipart("/api/ai/documents/analyze")
                        .file(file)
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("TREATMENT"));
    }

    @Test
    void conPetIdSePropagaAlUseCase() throws Exception {
        UUID petId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "examen.pdf", "application/pdf", pdfBytes());
        when(documentAnalysisUseCase.analyze(any(), any(), eq(petId))).thenReturn(DocumentExtractionDto.builder()
                .documentType(DocumentType.EXAM)
                .confianzaGeneral("ALTA")
                .build());

        mockMvc.perform(multipart("/api/ai/documents/analyze")
                        .file(file)
                        .param("petId", petId.toString())
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk());
    }

    @Test
    void petIdAjenoDevuelve404() throws Exception {
        UUID petId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "examen.pdf", "application/pdf", pdfBytes());
        when(documentAnalysisUseCase.analyze(any(), any(), eq(petId)))
                .thenThrow(new PetNotFoundException("La mascota no existe o no pertenece al usuario."));

        mockMvc.perform(multipart("/api/ai/documents/analyze")
                        .file(file)
                        .param("petId", petId.toString())
                        .header("Authorization", bearerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PET_NOT_FOUND"));
    }

    @Test
    void sinArchivoDevuelve400() throws Exception {
        mockMvc.perform(multipart("/api/ai/documents/analyze")
                        .header("Authorization", bearerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void mimeNoPermitidoDevuelve415() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "nota.txt", "text/plain",
                "esto no es un documento".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/ai/documents/analyze")
                        .file(file)
                        .header("Authorization", bearerToken))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_ATTACHMENT_TYPE"));
    }

    @Test
    void demasiadosBytesDevuelve413() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "examen.pdf", "application/pdf", pngOfSize(15_728_641));

        mockMvc.perform(multipart("/api/ai/documents/analyze")
                        .file(file)
                        .header("Authorization", bearerToken))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("ATTACHMENT_LIMIT_EXCEEDED"));
    }

    @Test
    void sinTokenDevuelve401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "examen.pdf", "application/pdf", pdfBytes());

        mockMvc.perform(multipart("/api/ai/documents/analyze").file(file))
                .andExpect(status().isUnauthorized());
    }
}
