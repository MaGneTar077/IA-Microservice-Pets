package com.myanimal.org.IA_service.application.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.model.AiMessage;
import com.myanimal.org.IA_service.domain.model.AiPart;
import com.myanimal.org.IA_service.domain.model.AiRequest;
import com.myanimal.org.IA_service.domain.model.AiResponse;
import com.myanimal.org.IA_service.domain.model.AiRole;
import com.myanimal.org.IA_service.domain.ports.in.ChatUseCase;
import com.myanimal.org.IA_service.domain.ports.out.AiModelPort;

@Service
public class ChatService implements ChatUseCase {

    private final AiModelPort aiModelPort;
    private final String systemPrompt;

    public ChatService(AiModelPort aiModelPort, @Qualifier("systemPromptText") String systemPrompt) {
        this.aiModelPort = aiModelPort;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public ChatResult chat(String userMessage) {
        AiMessage message = AiMessage.builder()
                .role(AiRole.USER)
                .parts(List.of(AiPart.builder().text(userMessage).build()))
                .build();

        AiRequest request = AiRequest.builder()
                .systemInstruction(systemPrompt)
                .messages(List.of(message))
                .build();

        AiResponse response = aiModelPort.generate(request);

        return ChatResult.builder()
                .reply(response.getText())
                .model(response.getModelUsed())
                .build();
    }
}
