package com.myanimal.org.IA_service.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myanimal.org.IA_service.application.dto.DocumentExtractionDto;
import com.myanimal.org.IA_service.domain.exception.DocumentAnalysisFailedException;
import com.myanimal.org.IA_service.domain.exception.PetNotFoundException;
import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.model.AiMessage;
import com.myanimal.org.IA_service.domain.model.AiPart;
import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.model.AiRole;
import com.myanimal.org.IA_service.domain.model.Pet;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.DocumentAnalysisUseCase;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;
import com.myanimal.org.IA_service.domain.ports.out.PetServicePort;

import lombok.extern.slf4j.Slf4j;

/**
 * No usa function calling ni escribe en medical-service (deliberado, ver CLAUDE.md):
 * solo extrae con structured output de Gemini y devuelve el DTO para que el móvil
 * pinte un formulario que el usuario confirma. Por eso no depende de
 * MedicalServicePort/CalendarServicePort — no hay forma de que este flujo escriba nada.
 */
@Slf4j
@Service
public class DocumentAnalysisService implements DocumentAnalysisUseCase {

    private final AiModelPort aiModelPort;
    private final PetServicePort petServicePort;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> documentSchema;
    private final String promptText;

    public DocumentAnalysisService(AiModelPort aiModelPort, PetServicePort petServicePort, ObjectMapper objectMapper,
            @Qualifier("medicalDocumentSchema") Map<String, Object> documentSchema,
            @Qualifier("documentExtractionPromptText") String promptText) {
        this.aiModelPort = aiModelPort;
        this.petServicePort = petServicePort;
        this.objectMapper = objectMapper;
        this.documentSchema = documentSchema;
        this.promptText = promptText;
    }

    @Override
    public DocumentExtractionDto analyze(UserContext userContext, UploadedFile file, UUID petId) {
        if (petId != null) {
            verifyPetOwnership(petId, userContext);
        }

        AiRequest request = AiRequest.builder()
                .systemInstruction(promptText)
                .messages(List.of(AiMessage.builder()
                        .role(AiRole.USER)
                        .parts(List.of(AiPart.ofBinary(file.mimeType(), file.content())))
                        .build()))
                .responseMimeType("application/json")
                .responseSchema(documentSchema)
                .build();

        return extractWithRetry(request);
    }

    private DocumentExtractionDto extractWithRetry(AiRequest request) {
        DocumentExtractionDto parsed = tryParse(aiModelPort.generate(request));
        if (parsed != null) {
            return parsed;
        }

        DocumentExtractionDto retryParsed = tryParse(aiModelPort.generate(request));
        if (retryParsed != null) {
            return retryParsed;
        }

        throw new DocumentAnalysisFailedException("No se pudo interpretar la respuesta del modelo tras reintentar.");
    }

    private DocumentExtractionDto tryParse(AiResponse response) {
        try {
            JsonNode root = objectMapper.readTree(response.getText());
            sanitizeLiteralNullStrings(root);
            return objectMapper.treeToValue(root, DocumentExtractionDto.class);
        } catch (JsonProcessingException ex) {
            log.debug("El JSON de Gemini no cumplió el esquema de extracción. Texto crudo: {}", response.getText());
            return null;
        }
    }

    /**
     * Pese a la regla del prompt, Gemini a veces escribe el string "null" en vez del null
     * real de JSON en un campo de tipo string (tiene sentido: el schema no deja que el
     * campo tome ningún otro tipo). Se corrige aquí, no agregando "nullable" al esquema
     * solamente, para no depender de que el modelo lo respete siempre.
     */
    private void sanitizeLiteralNullStrings(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            object.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                JsonNode child = object.get(fieldName);
                if (isLiteralNullString(child)) {
                    object.set(fieldName, NullNode.getInstance());
                } else {
                    sanitizeLiteralNullStrings(child);
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (isLiteralNullString(child)) {
                    array.set(i, NullNode.getInstance());
                } else {
                    sanitizeLiteralNullStrings(child);
                }
            }
        }
    }

    private boolean isLiteralNullString(JsonNode node) {
        return node.isTextual() && node.asText().strip().equalsIgnoreCase("null");
    }

    // Un 404/403 de pet-service y un ownerId distinto dan el mismo resultado (404
    // PET_NOT_FOUND): nunca se revela si el petId existe, mismo principio que en los tools.
    private void verifyPetOwnership(UUID petId, UserContext userContext) {
        Pet pet;
        try {
            pet = petServicePort.getPet(petId, userContext.rawJwt());
        } catch (ToolExecutionException ex) {
            throw new PetNotFoundException("La mascota no existe o no pertenece al usuario.");
        }
        if (!userContext.userId().equals(pet.getOwnerId())) {
            throw new PetNotFoundException("La mascota no existe o no pertenece al usuario.");
        }
    }
}
