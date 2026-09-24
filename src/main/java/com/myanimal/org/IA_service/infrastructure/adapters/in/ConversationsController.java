package com.myanimal.org.IA_service.infrastructure.adapters.in;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myanimal.org.IA_service.application.dto.ConversationSummaryDto;
import com.myanimal.org.IA_service.application.dto.MessageDto;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.ConversationQueryUseCase;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ai/conversations")
@RequiredArgsConstructor
public class ConversationsController {

    private final ConversationQueryUseCase conversationQueryUseCase;
    private final UserContextProvider userContextProvider;

    @GetMapping
    public ResponseEntity<List<ConversationSummaryDto>> listConversations(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        UserContext userContext = userContextProvider.current();
        return ResponseEntity.ok(conversationQueryUseCase.listConversations(userContext.userId(), limit, offset));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageDto>> listMessages(
            @PathVariable("id") UUID conversationId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        UserContext userContext = userContextProvider.current();
        return ResponseEntity.ok(
                conversationQueryUseCase.listMessages(userContext.userId(), conversationId, limit, offset));
    }
}
