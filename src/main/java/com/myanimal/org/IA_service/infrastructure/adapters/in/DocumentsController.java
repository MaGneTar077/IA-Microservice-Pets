package com.myanimal.org.IA_service.infrastructure.adapters.in;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.myanimal.org.IA_service.application.dto.DocumentExtractionDto;
import com.myanimal.org.IA_service.domain.exception.InvalidChatRequestException;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.DocumentAnalysisUseCase;
import com.myanimal.org.IA_service.infrastructure.adapters.in.attachment.DocumentFileValidator;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ai/documents")
@RequiredArgsConstructor
public class DocumentsController {

    private final DocumentAnalysisUseCase documentAnalysisUseCase;
    private final UserContextProvider userContextProvider;
    private final DocumentFileValidator documentFileValidator;

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentExtractionDto> analyze(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "petId", required = false) UUID petId) {

        if (file == null || file.isEmpty()) {
            throw new InvalidChatRequestException("Debes enviar un archivo.");
        }

        UploadedFile uploadedFile = documentFileValidator.validate(file);
        UserContext userContext = userContextProvider.current();
        DocumentExtractionDto result = documentAnalysisUseCase.analyze(userContext, uploadedFile, petId);
        return ResponseEntity.ok(result);
    }
}
