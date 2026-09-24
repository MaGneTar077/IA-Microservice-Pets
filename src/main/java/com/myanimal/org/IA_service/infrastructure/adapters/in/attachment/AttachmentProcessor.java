package com.myanimal.org.IA_service.infrastructure.adapters.in.attachment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.myanimal.org.IA_service.domain.exception.AttachmentsTooLargeException;
import com.myanimal.org.IA_service.domain.exception.InvalidChatRequestException;
import com.myanimal.org.IA_service.domain.exception.TooManyAttachmentsException;
import com.myanimal.org.IA_service.domain.exception.UnsupportedAttachmentTypeException;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.infrastructure.config.UploadProperties;

@Component
public class AttachmentProcessor {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif",
            "audio/wav", "audio/mp3", "audio/mpeg", "audio/aac", "audio/ogg", "audio/flac",
            "application/pdf");

    private final UploadProperties uploadProperties;

    public AttachmentProcessor(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    public List<UploadedFile> process(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return List.of();
        }
        if (files.length > uploadProperties.getMaxFilesPerRequest()) {
            throw new TooManyAttachmentsException(
                    "Máximo " + uploadProperties.getMaxFilesPerRequest() + " archivos por petición.");
        }

        List<UploadedFile> uploadedFiles = new ArrayList<>();
        long totalBytes = 0;
        for (MultipartFile file : files) {
            byte[] content = readBytes(file);
            totalBytes += content.length;
            String mimeType = MagicByteMimeDetector.detect(content)
                    .filter(ALLOWED_MIME_TYPES::contains)
                    .orElseThrow(() -> new UnsupportedAttachmentTypeException(
                            "El archivo no es de un tipo permitido (imagen, audio o PDF)."));
            uploadedFiles.add(new UploadedFile(content, mimeType));
        }

        if (totalBytes > uploadProperties.getMaxTotalBytes()) {
            throw new AttachmentsTooLargeException(
                    "Los adjuntos superan el máximo de " + uploadProperties.getMaxTotalBytes() + " bytes por petición.");
        }

        return uploadedFiles;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new InvalidChatRequestException("No se pudo leer uno de los archivos adjuntos.");
        }
    }
}
