package com.myanimal.org.IA_service.domain.ports.in;

import com.myanimal.org.IA_service.application.dto.VerificationAnalyzeRequest;
import com.myanimal.org.IA_service.application.dto.VerificationAnalyzeResponse;

public interface VerificationAnalyzeUseCase {

    VerificationAnalyzeResponse analyze(VerificationAnalyzeRequest request);
}
