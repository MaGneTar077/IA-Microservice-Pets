package com.myanimal.org.IA_service.infrastructure.adapters.in.attachment;

import java.io.IOException;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.myanimal.org.IA_service.domain.exception.AttachmentsTooLargeException;
import com.myanimal.org.IA_service.domain.exception.InvalidChatRequestException;
import com.myanimal.org.IA_service.domain.exception.UnsupportedAttachmentTypeException;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.infrastructure.config.DocumentsProperties;

/**
 * Validación de un único archivo para /api/ai/documents/analyze: PDF o imagen, sin audio,
 * con los límites propios de documents.* (no los de uploads.* del chat multimodal, que
 * validan varios archivos por petición).
 */
@Component
public class DocumentFileValidator {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif");

    private final DocumentsProperties documentsProperties;

    public DocumentFileValidator(DocumentsProperties documentsProperties) {
        this.documentsProperties = documentsProperties;
    }

    public UploadedFile validate(MultipartFile file) {
        byte[] content = readBytes(file);
        if (content.length > documentsProperties.getMaxFileSizeBytes()) {
            throw new AttachmentsTooLargeException(
                    "El archivo supera el máximo de " + documentsProperties.getMaxFileSizeBytes() + " bytes.");
        }
        String mimeType = MagicByteMimeDetector.detect(content)
                .filter(ALLOWED_MIME_TYPES::contains)
                .orElseThrow(() -> new UnsupportedAttachmentTypeException(
                        "El archivo debe ser un PDF o una imagen (PNG, JPEG, WEBP, HEIC o HEIF)."));
        return new UploadedFile(content, mimeType);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new InvalidChatRequestException("No se pudo leer el archivo.");
        }
    }
}
