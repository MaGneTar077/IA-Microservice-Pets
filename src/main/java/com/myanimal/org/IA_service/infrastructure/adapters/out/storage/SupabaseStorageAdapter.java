package com.myanimal.org.IA_service.infrastructure.adapters.out.storage;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.myanimal.org.IA_service.domain.exception.AttachmentStorageException;
import com.myanimal.org.IA_service.domain.model.StoredFile;
import com.myanimal.org.IA_service.domain.ports.out.FileStoragePort;
import com.myanimal.org.IA_service.infrastructure.adapters.out.storage.dto.SupabaseSignedUrlRequest;
import com.myanimal.org.IA_service.infrastructure.adapters.out.storage.dto.SupabaseSignedUrlResponse;
import com.myanimal.org.IA_service.infrastructure.config.SupabaseProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SupabaseStorageAdapter implements FileStoragePort {

    private static final Map<String, String> EXTENSIONS_BY_MIME_TYPE = Map.ofEntries(
            Map.entry("image/png", "png"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/heic", "heic"),
            Map.entry("image/heif", "heif"),
            Map.entry("audio/wav", "wav"),
            Map.entry("audio/mp3", "mp3"),
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/aac", "aac"),
            Map.entry("audio/ogg", "ogg"),
            Map.entry("audio/flac", "flac"),
            Map.entry("application/pdf", "pdf"));

    private final WebClient supabaseWebClient;
    private final SupabaseProperties properties;

    public SupabaseStorageAdapter(WebClient supabaseWebClient, SupabaseProperties properties) {
        this.supabaseWebClient = supabaseWebClient;
        this.properties = properties;
    }

    @Override
    public StoredFile upload(byte[] content, String mimeType, UUID userId) {
        String objectPath = userId + "/" + UUID.randomUUID() + "." + extensionFor(mimeType);
        try {
            supabaseWebClient.post()
                    .uri("/storage/v1/object/{bucket}/{path}", properties.getBucket(), objectPath)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getServiceKey())
                    .contentType(MediaType.parseMediaType(mimeType))
                    .bodyValue(content)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException ex) {
            log.warn("Supabase Storage respondió {} al subir un archivo", ex.getStatusCode().value());
            throw new AttachmentStorageException("No se pudo subir el archivo adjunto.", ex);
        }
        return new StoredFile(objectPath, mimeType);
    }

    @Override
    public byte[] download(String objectPath) {
        try {
            return supabaseWebClient.get()
                    .uri("/storage/v1/object/{bucket}/{path}", properties.getBucket(), objectPath)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getServiceKey())
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (WebClientResponseException ex) {
            log.warn("Supabase Storage respondió {} al descargar un archivo", ex.getStatusCode().value());
            throw new AttachmentStorageException("No se pudo descargar el archivo adjunto.", ex);
        }
    }

    @Override
    public String signedUrl(String objectPath, Duration ttl) {
        try {
            SupabaseSignedUrlResponse response = supabaseWebClient.post()
                    .uri("/storage/v1/object/sign/{bucket}/{path}", properties.getBucket(), objectPath)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getServiceKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(SupabaseSignedUrlRequest.builder().expiresIn(ttl.toSeconds()).build())
                    .retrieve()
                    .bodyToMono(SupabaseSignedUrlResponse.class)
                    .block();
            return properties.getUrl() + response.getSignedUrl();
        } catch (WebClientResponseException ex) {
            log.warn("Supabase Storage respondió {} al firmar una URL", ex.getStatusCode().value());
            throw new AttachmentStorageException("No se pudo generar la URL firmada.", ex);
        }
    }

    private String extensionFor(String mimeType) {
        return EXTENSIONS_BY_MIME_TYPE.getOrDefault(mimeType, "bin");
    }
}
