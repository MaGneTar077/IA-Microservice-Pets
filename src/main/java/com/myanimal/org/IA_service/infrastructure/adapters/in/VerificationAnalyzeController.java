package com.myanimal.org.IA_service.infrastructure.adapters.in;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myanimal.org.IA_service.application.dto.VerificationAnalyzeRequest;
import com.myanimal.org.IA_service.application.dto.VerificationAnalyzeResponse;
import com.myanimal.org.IA_service.domain.ports.in.VerificationAnalyzeUseCase;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/internal/verification")
@RequiredArgsConstructor
public class VerificationAnalyzeController {

    private final VerificationAnalyzeUseCase verificationAnalyzeUseCase;

    @PostMapping("/analyze")
    public ResponseEntity<VerificationAnalyzeResponse> analyze(@RequestBody VerificationAnalyzeRequest request) {
        return ResponseEntity.ok(verificationAnalyzeUseCase.analyze(request));
    }
}
