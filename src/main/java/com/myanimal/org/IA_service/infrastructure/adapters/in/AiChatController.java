package com.myanimal.org.IA_service.infrastructure.adapters.in;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.ChatUseCase;
import com.myanimal.org.IA_service.infrastructure.adapters.in.dto.ChatRequestDto;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final ChatUseCase chatUseCase;
    private final UserContextProvider userContextProvider;

    @PostMapping("/text")
    public ResponseEntity<ChatResult> chatText(@Valid @RequestBody ChatRequestDto request) {
        UserContext userContext = userContextProvider.current();
        ChatResult result = chatUseCase.chat(userContext, request.getConversationId(), request.getMessage());
        return ResponseEntity.ok(result);
    }
}
