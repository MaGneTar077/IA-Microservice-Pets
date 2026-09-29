package com.myanimal.org.IA_service.infrastructure.adapters.in;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.PendingActionUseCase;
import com.myanimal.org.IA_service.infrastructure.security.UserContextProvider;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ai/actions")
@RequiredArgsConstructor
public class ActionsController {

    private final PendingActionUseCase pendingActionUseCase;
    private final UserContextProvider userContextProvider;

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ChatResult> confirm(@PathVariable("id") UUID id) {
        UserContext userContext = userContextProvider.current();
        return ResponseEntity.ok(pendingActionUseCase.confirm(userContext, id));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ChatResult> reject(@PathVariable("id") UUID id) {
        UserContext userContext = userContextProvider.current();
        return ResponseEntity.ok(pendingActionUseCase.reject(userContext, id));
    }
}
