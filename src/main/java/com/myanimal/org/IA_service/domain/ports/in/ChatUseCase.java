package com.myanimal.org.IA_service.domain.ports.in;

import java.util.UUID;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.model.UserContext;

public interface ChatUseCase {

    ChatResult chat(UserContext userContext, UUID conversationId, String userMessage);
}
