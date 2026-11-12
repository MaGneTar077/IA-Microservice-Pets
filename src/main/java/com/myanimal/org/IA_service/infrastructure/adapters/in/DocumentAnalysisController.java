package com.myanimal.org.IA_service.infrastructure.adapters.in;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myanimal.org.IA_service.application.dto.DocumentAnalysisRequest;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysisResponse;
import com.myanimal.org.IA_service.domain.ports.in.DocumentAnalysisUseCase;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/internal/ai")
@RequiredArgsConstructor
public class DocumentAnalysisController {

    private final DocumentAnalysisUseCase documentAnalysisUseCase;

    @PostMapping("/document-analysis")
    public ResponseEntity<DocumentAnalysisResponse> analyze(@RequestBody DocumentAnalysisRequest request) {
        return ResponseEntity.ok(documentAnalysisUseCase.analyze(request));
    }
}
