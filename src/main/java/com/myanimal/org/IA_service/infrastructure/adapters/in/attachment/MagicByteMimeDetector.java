package com.myanimal.org.IA_service.infrastructure.adapters.in.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * Determina el MIME real de un archivo por sus primeros bytes (magic numbers),
 * no por la extensión ni por el Content-Type que manda el cliente. Cero
 * dependencias nuevas: firmas conocidas de los formatos de la lista blanca.
 */
public final class MagicByteMimeDetector {

    private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs");
    private static final Set<String> HEIF_BRANDS = Set.of("mif1", "msf1");

    private MagicByteMimeDetector() {
    }

    public static Optional<String> detect(byte[] content) {
        if (content == null || content.length < 4) {
            return Optional.empty();
        }

        if (startsWith(content, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of("image/png");
        }
        if (startsWith(content, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (asciiAt(content, 0, "RIFF") && asciiAt(content, 8, "WEBP")) {
            return Optional.of("image/webp");
        }
        if (asciiAt(content, 0, "RIFF") && asciiAt(content, 8, "WAVE")) {
            return Optional.of("audio/wav");
        }
        if (asciiAt(content, 4, "ftyp") && content.length >= 12) {
            String brand = new String(content, 8, 4, StandardCharsets.US_ASCII).trim().toLowerCase();
            if (HEIC_BRANDS.contains(brand)) {
                return Optional.of("image/heic");
            }
            if (HEIF_BRANDS.contains(brand)) {
                return Optional.of("image/heif");
            }
        }
        if (asciiAt(content, 0, "%PDF")) {
            return Optional.of("application/pdf");
        }
        if (asciiAt(content, 0, "OggS")) {
            return Optional.of("audio/ogg");
        }
        if (asciiAt(content, 0, "fLaC")) {
            return Optional.of("audio/flac");
        }
        if (asciiAt(content, 0, "ID3")) {
            return Optional.of("audio/mpeg");
        }
        // ADTS (AAC): FFFx donde x tiene el nibble alto en 1 (sync word de 12 bits).
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xF0) == 0xF0) {
            return Optional.of("audio/aac");
        }
        // MPEG frame sync sin tag ID3 (11 bits: FFEx-FFFx ya cubierto arriba por AAC).
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xE0) == 0xE0) {
            return Optional.of("audio/mpeg");
        }

        return Optional.empty();
    }

    private static boolean startsWith(byte[] content, int offset, int... expectedUnsignedBytes) {
        if (content.length < offset + expectedUnsignedBytes.length) {
            return false;
        }
        for (int i = 0; i < expectedUnsignedBytes.length; i++) {
            if ((content[offset + i] & 0xFF) != expectedUnsignedBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiAt(byte[] content, int offset, String ascii) {
        byte[] expected = ascii.getBytes(StandardCharsets.US_ASCII);
        if (content.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (content[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
