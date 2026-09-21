package com.myanimal.org.IA_service.domain.ports.out;

import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;

public interface AiModelPort {

    AiResponse generate(AiRequest request);
}
