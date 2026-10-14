package com.myanimal.org.IA_service.domain.ports.in;

import java.util.UUID;

import com.myanimal.org.IA_service.application.dto.DocumentExtractionDto;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.domain.model.UserContext;

public interface DocumentAnalysisUseCase {

    DocumentExtractionDto analyze(UserContext userContext, UploadedFile file, UUID petId);
}
