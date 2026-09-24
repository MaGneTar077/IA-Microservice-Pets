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
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.ports.in.ChatUseCase;
import com.myanimal.org.IA_service.infrastructure.adapters.in.attachment.AttachmentProcessor;
import com.myanimal.org.IA_service.infrastructure.config.SecurityConfig;
import com.myanimal.org.IA_service.infrastructure.config.UploadProperties;
import com.myanimal.org.IA_service.infrastructure.security.JwtProperties;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Usa el AttachmentProcessor y el UploadProperties REALES (no mockeados) para
 * ejercitar de verdad la detección de MIME por magic bytes y los límites de
 * cantidad/tamaño en el endpoint multipart.
 */
@WebMvcTest(AiChatController.class)
@Import({ SecurityConfig.class, JwtProperties.class, AttachmentProcessor.class, UploadProperties.class })
@ActiveProfiles("test")
class AiChatMultipartControllerTest {

    private static final byte[] PNG_HEADER = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatUseCase chatUseCase;

    @MockitoBean
    private UserContextProvider userContextProvider;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    private final UUID userId = UUID.randomUUID();
    private String bearerToken;

    @BeforeEach
    void setUp() {
        when(userContextProvider.current()).thenReturn(new com.myanimal.org.IA_service.domain.model.UserContext(
                userId, "fake.jwt.token"));

        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        bearerToken = "Bearer " + Jwts.builder()
                .claim("id", userId.toString())
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    private byte[] pngOfSize(int size) {
        byte[] content = new byte[size];
        System.arraycopy(PNG_HEADER, 0, content, 0, PNG_HEADER.length);
        return content;
    }

    @Test
    void soloTextoDevuelve200() throws Exception {
        when(chatUseCase.chat(any(), isNull(), anyString(), eq(java.util.List.of()))).thenReturn(ChatResult.builder()
                .conversationId(UUID.randomUUID())
                .reply("hola")
                .model("gemini-test")
                .build());

        mockMvc.perform(multipart("/api/ai/chat")
                        .header("Authorization", bearerToken)
                        .param("message", "hola"))
                .andExpect(status().isOk());
    }

    @Test
    void soloArchivoDevuelve200() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "foto.png", "image/png", pngOfSize(100));
        when(chatUseCase.chat(any(), isNull(), isNull(), any())).thenReturn(ChatResult.builder()
                .conversationId(UUID.randomUUID())
                .reply("Parece un labrador.")
                .model("gemini-test")
                .build());

        mockMvc.perform(multipart("/api/ai/chat")
                        .file(file)
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Parece un labrador."));
    }

    @Test
    void textoYArchivoDevuelve200() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "foto.png", "image/png", pngOfSize(100));
        when(chatUseCase.chat(any(), isNull(), anyString(), any())).thenReturn(ChatResult.builder()
                .conversationId(UUID.randomUUID())
                .reply("Parece un labrador.")
                .model("gemini-test")
                .build());

        mockMvc.perform(multipart("/api/ai/chat")
                        .file(file)
                        .param("message", "¿qué raza es?")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk());
    }

    @Test
    void sinTextoYSinArchivosDevuelve400() throws Exception {
        mockMvc.perform(multipart("/api/ai/chat")
                        .header("Authorization", bearerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void mimeNoPermitidoDevuelve415() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "nota.txt", "text/plain",
                "esto no es una imagen".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/ai/chat")
                        .file(file)
                        .header("Authorization", bearerToken))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_ATTACHMENT_TYPE"));
    }

    @Test
    void demasiadosBytesDevuelve413() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "foto.png", "image/png", pngOfSize(15_728_641));

        mockMvc.perform(multipart("/api/ai/chat")
                        .file(file)
                        .header("Authorization", bearerToken))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("ATTACHMENT_LIMIT_EXCEEDED"));
    }

    @Test
    void sinTokenDevuelve401() throws Exception {
        mockMvc.perform(multipart("/api/ai/chat")
                        .param("message", "hola"))
                .andExpect(status().isUnauthorized());
    }
}
