package com.myanimal.org.IA_service.domain.ports.in;

import com.myanimal.org.IA_service.application.dto.DocumentAnalysisRequest;
import com.myanimal.org.IA_service.application.dto.DocumentAnalysisResponse;

public interface DocumentAnalysisUseCase {

    DocumentAnalysisResponse analyze(DocumentAnalysisRequest request);
}
