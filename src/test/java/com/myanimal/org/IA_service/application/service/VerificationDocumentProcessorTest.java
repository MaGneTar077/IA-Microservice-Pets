package com.myanimal.org.IA_service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myanimal.org.IA_service.application.dto.DeclaredData;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysis;
import com.myanimal.org.IA_service.application.dto.DocumentExtraction;
import com.myanimal.org.IA_service.application.dto.DocumentExtractionResult;
import com.myanimal.org.IA_service.application.dto.ExtractedData;
import com.myanimal.org.IA_service.application.dto.VerificationDocumentResult;
import com.myanimal.org.IA_service.domain.model.DocumentType;
import com.myanimal.org.IA_service.domain.model.DownloadedFile;
import com.myanimal.org.IA_service.domain.ports.out.RemoteFileDownloadPort;
import com.myanimal.org.IA_service.infrastructure.config.DocumentAnalysisProperties;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class VerificationDocumentProcessorTest {

    @Mock
    private RemoteFileDownloadPort remoteFileDownloadPort;

    @Mock
    private GeminiDocumentExtractor geminiDocumentExtractor;

    @Mock
    private DocumentCheckEngine documentCheckEngine;

    private DocumentAnalysisProperties properties;
    private DocumentAnalysisCache cache;
    private VerificationDocumentProcessor processor;

    private static final byte[] PDF_BYTES = pdfBytes();

    private static byte[] pdfBytes() {
        return "%PDF-1.4 contenido".getBytes();
    }

    @BeforeEach
    void setUp() {
        properties = new DocumentAnalysisProperties();
        properties.setAllowedFileHosts(List.of("storage.test"));
        properties.setMaxFileMb(10);
        properties.setMaxPages(5);
        cache = new DocumentAnalysisCache(java.time.Clock.systemUTC());
        processor = new VerificationDocumentProcessor(remoteFileDownloadPort, properties, geminiDocumentExtractor,
                documentCheckEngine, cache);
    }

    @Test
    void tipoNoSoportadoDevuelveSupportedFalseSinDescargar() {
        VerificationDocumentResult result = processor.process(DocumentType.FACADE_PHOTO,
                "https://storage.test/x", null);

        assertThat(result.isSupported()).isFalse();
        assertThat(result.getError()).isNull();
        verify(remoteFileDownloadPort, never()).download(any());
    }

    @Test
    void hostNoPermitidoDevuelveError() {
        VerificationDocumentResult result = processor.process(DocumentType.RUT, "https://evil.test/x", null);

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getError()).isNotNull();
        assertThat(result.getAnalysis()).isNull();
        verify(remoteFileDownloadPort, never()).download(any());
    }

    @Test
    void documentoValidoDevuelveAnalysis() {
        when(remoteFileDownloadPort.download("https://storage.test/x"))
                .thenReturn(new DownloadedFile(PDF_BYTES, "application/pdf"));
        DocumentExtraction extraction = DocumentExtraction.builder().detectedType("RUT").legible(true).build();
        when(geminiDocumentExtractor.extract(eq(DocumentType.RUT), any(), any(), eq("application/pdf")))
                .thenReturn(DocumentExtractionResult.builder().extraction(extraction).modelUsed("gemini-test").build());
        DocumentAnalysis analysis = DocumentAnalysis.builder().recommendation("LIKELY_VALID").build();
        when(documentCheckEngine.buildAnalysis(eq(DocumentType.RUT), any(), eq(extraction))).thenReturn(analysis);

        VerificationDocumentResult result = processor.process(DocumentType.RUT, "https://storage.test/x", null);

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getError()).isNull();
        assertThat(result.getAnalysis()).isEqualTo(analysis);
    }

    @Test
    void falloDeDescargaDevuelveErrorSinTumbarElResultado() {
        when(remoteFileDownloadPort.download("https://storage.test/x"))
                .thenThrow(new com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException(
                        "No se pudo descargar el documento."));

        VerificationDocumentResult result = processor.process(DocumentType.RUT, "https://storage.test/x", null);

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getError()).isEqualTo("No se pudo descargar el documento.");
        assertThat(result.getAnalysis()).isNull();
    }

    @Test
    void legalRepIdNuncaLogueaElNumeroDeCedulaAunqueFalle() {
        Logger logger = (Logger) LoggerFactory.getLogger(VerificationDocumentProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        String fakeCedula = "1234567890";
        when(remoteFileDownloadPort.download("https://storage.test/x"))
                .thenThrow(new RuntimeException("fallo simulado con cédula " + fakeCedula));

        try {
            DeclaredData declared = DeclaredData.builder().legalRepresentativeName("Ana Pérez").build();
            VerificationDocumentResult result = processor.process(DocumentType.LEGAL_REP_ID,
                    "https://storage.test/x", declared);

            assertThat(result.getError()).isEqualTo("Error inesperado procesando el documento.");
            assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains(fakeCedula));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
