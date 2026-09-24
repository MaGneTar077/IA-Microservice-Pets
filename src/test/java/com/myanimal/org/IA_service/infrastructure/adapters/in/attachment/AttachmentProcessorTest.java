package com.myanimal.org.IA_service.infrastructure.adapters.in.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.myanimal.org.IA_service.domain.exception.AttachmentsTooLargeException;
import com.myanimal.org.IA_service.domain.exception.TooManyAttachmentsException;
import com.myanimal.org.IA_service.domain.exception.UnsupportedAttachmentTypeException;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.infrastructure.config.UploadProperties;

class AttachmentProcessorTest {

    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00
    };

    private UploadProperties uploadProperties() {
        UploadProperties properties = new UploadProperties();
        properties.setMaxFilesPerRequest(2);
        properties.setMaxTotalBytes(20);
        return properties;
    }

    private AttachmentProcessor processor() {
        return new AttachmentProcessor(uploadProperties());
    }

    @Test
    void sinArchivosDevuelveListaVacia() {
        assertThat(processor().process(null)).isEmpty();
        assertThat(processor().process(new MultipartFile[0])).isEmpty();
    }

    @Test
    void archivoValidoSeDetectaPorContenidoNoPorElContentTypeDelCliente() {
        MultipartFile file = new MockMultipartFile("files", "foto.txt", "text/plain", PNG_BYTES);

        var result = processor().process(new MultipartFile[] { file });

        assertThat(result).hasSize(1);
        UploadedFile uploaded = result.get(0);
        assertThat(uploaded.mimeType()).isEqualTo("image/png");
        assertThat(uploaded.content()).isEqualTo(PNG_BYTES);
    }

    @Test
    void masArchivosQueElMaximoLanzaTooManyAttachments() {
        MultipartFile file = new MockMultipartFile("files", "foto.png", "image/png", PNG_BYTES);

        assertThatThrownBy(() -> processor().process(new MultipartFile[] { file, file, file }))
                .isInstanceOf(TooManyAttachmentsException.class);
    }

    @Test
    void tipoNoPermitidoLanzaUnsupportedAttachmentType() {
        byte[] garbage = { 0x00, 0x01, 0x02, 0x03 };
        MultipartFile file = new MockMultipartFile("files", "raro.bin", "application/octet-stream", garbage);

        assertThatThrownBy(() -> processor().process(new MultipartFile[] { file }))
                .isInstanceOf(UnsupportedAttachmentTypeException.class);
    }

    @Test
    void sumaDeBytesPorEncimaDelMaximoLanzaAttachmentsTooLarge() {
        byte[] bigPng = new byte[25];
        System.arraycopy(PNG_BYTES, 0, bigPng, 0, PNG_BYTES.length);
        MultipartFile file = new MockMultipartFile("files", "foto.png", "image/png", bigPng);

        assertThatThrownBy(() -> processor().process(new MultipartFile[] { file }))
                .isInstanceOf(AttachmentsTooLargeException.class);
    }
}
