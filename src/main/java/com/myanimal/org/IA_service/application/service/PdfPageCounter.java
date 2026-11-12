package com.myanimal.org.IA_service.application.service;

import java.io.IOException;
import java.util.Optional;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import lombok.extern.slf4j.Slf4j;

/**
 * Si el PDF no se puede abrir, no es un error de validación: block 3/4 (extracción con
 * Gemini) es quien decide "legible: false". Aquí solo se protege MAX_PAGES cuando el
 * conteo sí se puede calcular.
 */
@Slf4j
final class PdfPageCounter {

    private PdfPageCounter() {
    }

    static Optional<Integer> countPages(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return Optional.of(document.getNumberOfPages());
        } catch (IOException ex) {
            log.warn("No se pudo abrir el PDF para contar páginas.");
            return Optional.empty();
        }
    }
}
