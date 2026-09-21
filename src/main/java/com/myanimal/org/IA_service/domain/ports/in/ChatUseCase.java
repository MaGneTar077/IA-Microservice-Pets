package com.myanimal.org.IA_service.domain.ports.in;

import com.myanimal.org.IA_service.application.dto.ChatResult;

public interface ChatUseCase {

    ChatResult chat(String userMessage);
}
