package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysisRequest;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysisResponse;
import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisValidationException;
import com.myanimal.org.IA_service.domain.exception.FileTooLargeException;
import com.myanimal.org.IA_service.domain.exception.FileUrlNotAllowedException;
import com.myanimal.org.IA_service.domain.exception.UnsupportedFileTypeException;
import com.myanimal.org.IA_service.domain.ports.out.RemoteFileDownloadPort;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisProperties;

@ExtendWith(MockitoExtension.class)
class DocumentAnalysisServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T17:00:00Z"), ZoneOffset.UTC);

    @Mock
    private RemoteFileDownloadPort remoteFileDownloadPort;

    private DocumentAnalysisProperties documentAnalysisProperties;
    private DocumentAnalysisService service;

    @BeforeEach
    void setUp() {
        documentAnalysisProperties = new DocumentAnalysisProperties();
        documentAnalysisProperties.setAllowedFileHosts(List.of("storage.test"));
        documentAnalysisProperties.setMaxFileMb(10);
        documentAnalysisProperties.setMaxPages(5);
        service = new DocumentAnalysisService(clock, remoteFileDownloadPort, documentAnalysisProperties);
    }

    private DocumentAnalysisRequest.DocumentAnalysisRequestBuilder validRequest() {
        return DocumentAnalysisRequest.builder()
                .documentId(UUID.randomUUID())
                .veterinaryId(UUID.randomUUID())
                .documentType("RUT")
                .fileUrl("https://storage.test/signed-url")
                .mimeType("application/pdf")
                .sha256("abc123")
                .declared(DeclaredData.builder().clinicName("Clínica Patitas").nit("900123456-7").build());
    }

    @Test
    void tipoDeDocumentoNoSoportadoDevuelveSupportedFalseSinAnalisisNiDescarga() {
        DocumentAnalysisRequest request = validRequest().documentType("CEDULA").build();

        DocumentAnalysisResponse response = service.analyze(request);

        assertThat(response.isSupported()).isFalse();
        assertThat(response.getAnalysis()).isNull();
        assertThat(response.getDocumentId()).isEqualTo(request.getDocumentId());
        assertThat(response.getAnalyzedAt()).isEqualTo(Instant.parse("2026-11-12T17:00:00Z"));
        verify(remoteFileDownloadPort, never()).download(any());
    }

    @Test
    void tipoSoportadoAunNoImplementadoLanzaAnalysisUnavailableTrasDescargar() {
        DocumentAnalysisRequest request = validRequest().build();
        when(remoteFileDownloadPort.download(request.getFileUrl())).thenReturn(new byte[] { 1, 2, 3 });

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(AnalysisUnavailableException.class);
    }

    @Test
    void sinDocumentIdLanzaValidationException() {
        DocumentAnalysisRequest request = validRequest().documentId(null).build();

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(DocumentAnalysisValidationException.class);
    }

    @Test
    void sinFileUrlLanzaValidationException() {
        DocumentAnalysisRequest request = validRequest().fileUrl(" ").build();

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(DocumentAnalysisValidationException.class);
    }

    @Test
    void sinDeclaredLanzaValidationException() {
        DocumentAnalysisRequest request = validRequest().declared(null).build();

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(DocumentAnalysisValidationException.class);
    }

    @Test
    void mimeTypeNoPermitidoLanzaUnsupportedFileTypeException() {
        DocumentAnalysisRequest request = validRequest().mimeType("application/zip").build();

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(UnsupportedFileTypeException.class);
        verify(remoteFileDownloadPort, never()).download(any());
    }

    @Test
    void hostFueraDeLaListaBlancaLanzaFileUrlNotAllowedException() {
        DocumentAnalysisRequest request = validRequest().fileUrl("https://evil.test/file.pdf").build();

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(FileUrlNotAllowedException.class);
        verify(remoteFileDownloadPort, never()).download(any());
    }

    @Test
    void esquemaHttpEnFileUrlLanzaFileUrlNotAllowedException() {
        DocumentAnalysisRequest request = validRequest().fileUrl("http://storage.test/signed-url").build();

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(FileUrlNotAllowedException.class);
    }

    @Test
    void pdfConMasPaginasQueElMaximoLanzaFileTooLargeException() throws Exception {
        DocumentAnalysisRequest request = validRequest().build();
        when(remoteFileDownloadPort.download(request.getFileUrl())).thenReturn(sixPagePdfBytes());

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOf(FileTooLargeException.class);
    }

    private byte[] sixPagePdfBytes() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            for (int i = 0; i < 6; i++) {
                document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
