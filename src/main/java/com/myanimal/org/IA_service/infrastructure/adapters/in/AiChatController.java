package com.myanimal.org.IA_service.infrastructure.adapters.in;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.ports.in.ChatUseCase;
import com.myanimal.org.IA_service.infrastructure.adapters.in.dto.ChatRequestDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final ChatUseCase chatUseCase;

    @PostMapping("/text")
    public ResponseEntity<ChatResult> chatText(@Valid @RequestBody ChatRequestDto request) {
        return ResponseEntity.ok(chatUseCase.chat(request.getMessage()));
    }
}
