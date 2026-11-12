package com.myanimal.org.IA_service.infrastructure.adapters.out.storage;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.FileTooLargeException;
import com.myanimal.org.IA_service.domain.ports.out.RemoteFileDownloadPort;

import lombok.extern.slf4j.Slf4j;

/**
 * Descarga el documento desde la URL firmada que manda Veterinary. Nunca se loguea la URL
 * (dato sensible de corta duración, ver privacidad en el checkpoint de la etapa).
 */
@Slf4j
@Component
public class SignedUrlDownloadAdapter implements RemoteFileDownloadPort {

    private final WebClient documentDownloadWebClient;

    public SignedUrlDownloadAdapter(WebClient documentDownloadWebClient) {
        this.documentDownloadWebClient = documentDownloadWebClient;
    }

    @Override
    public byte[] download(String fileUrl) {
        try {
            return documentDownloadWebClient.get()
                    .uri(fileUrl)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (DataBufferLimitException ex) {
            throw new FileTooLargeException("El archivo supera el tamaño máximo permitido.");
        } catch (WebClientResponseException | WebClientRequestException ex) {
            // El límite de tamaño se detecta al decodificar el cuerpo, en pleno transporte
            // reactivo: llega envuelto en WebClientRequestException, no como DataBufferLimitException
            // directa.
            if (ex.getCause() instanceof DataBufferLimitException) {
                throw new FileTooLargeException("El archivo supera el tamaño máximo permitido.");
            }
            log.warn("No se pudo descargar el documento de verificación.");
            throw new AnalysisUnavailableException("No se pudo descargar el documento.", ex);
        }
    }
}
