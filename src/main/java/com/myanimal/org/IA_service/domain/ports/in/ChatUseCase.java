package com.myanimal.org.IA_service.domain.ports.in;

import java.util.List;
import java.util.UUID;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.model.UploadedFile;
import com.myanimal.org.IA_service.domain.model.UserContext;

public interface ChatUseCase {

    ChatResult chat(UserContext userContext, UUID conversationId, String userMessage, List<UploadedFile> attachments);
}
