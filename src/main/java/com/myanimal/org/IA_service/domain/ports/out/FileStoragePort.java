package com.myanimal.org.IA_service.domain.ports.out;

import java.time.Duration;
import java.util.UUID;

import com.myanimal.org.IA_service.domain.model.StoredFile;

public interface FileStoragePort {

    StoredFile upload(byte[] content, String mimeType, UUID userId);

    byte[] download(String objectPath);

    String signedUrl(String objectPath, Duration ttl);
}
