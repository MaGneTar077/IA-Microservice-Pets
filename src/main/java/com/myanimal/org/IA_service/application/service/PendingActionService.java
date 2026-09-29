package com.myanimal.org.IA_service.application.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.myanimal.org.IA_service.application.dto.ChatResult;
import com.myanimal.org.IA_service.domain.exception.PendingActionExpiredException;
import com.myanimal.org.IA_service.domain.exception.PendingActionNotFoundException;
import com.myanimal.org.IA_service.domain.model.Message;
import com.myanimal.org.IA_service.domain.model.MessageRole;
import com.myanimal.org.IA_service.domain.model.PendingAction;
import com.myanimal.org.IA_service.domain.model.PendingActionStatus;
import com.myanimal.org.IA_service.domain.model.UserContext;
import com.myanimal.org.IA_service.domain.ports.in.PendingActionUseCase;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;
import com.myanimal.org.IA_service.domain.ports.out.PendingActionRepositoryPort;

@Service
public class PendingActionService implements PendingActionUseCase {

    private static final String REJECTED_MESSAGE = "De acuerdo, no realicé esa acción.";

    private final PendingActionRepositoryPort pendingActionRepositoryPort;
    private final ConversationRepositoryPort conversationRepositoryPort;
    private final ChatLoopRunner chatLoopRunner;
    private final Clock clock;

    public PendingActionService(PendingActionRepositoryPort pendingActionRepositoryPort,
            ConversationRepositoryPort conversationRepositoryPort, ChatLoopRunner chatLoopRunner, Clock clock) {
        this.pendingActionRepositoryPort = pendingActionRepositoryPort;
        this.conversationRepositoryPort = conversationRepositoryPort;
        this.chatLoopRunner = chatLoopRunner;
        this.clock = clock;
    }

    @Override
    public ChatResult confirm(UserContext userContext, UUID pendingActionId) {
        PendingAction pending = findPendiente(userContext, pendingActionId);

        if (pending.getExpiresAt().isBefore(clock.instant())) {
            pendingActionRepositoryPort.updateStatus(pendingActionId, PendingActionStatus.EXPIRADA, clock.instant());
            throw new PendingActionExpiredException("La acción ya expiró.");
        }

        pendingActionRepositoryPort.updateStatus(pendingActionId, PendingActionStatus.CONFIRMADA, clock.instant());
        return chatLoopRunner.confirmPendingAction(userContext, pending);
    }

    @Override
    public ChatResult reject(UserContext userContext, UUID pendingActionId) {
        PendingAction pending = findPendiente(userContext, pendingActionId);

        pendingActionRepositoryPort.updateStatus(pendingActionId, PendingActionStatus.RECHAZADA, clock.instant());

        // No se llama a Gemini para esto: es gastar dinero en decir "de acuerdo".
        Message rejectionTurn = Message.builder()
                .role(MessageRole.MODEL)
                .contenido(REJECTED_MESSAGE)
                .adjuntos(List.of())
                .build();
        conversationRepositoryPort.append(pending.getConversationId(), rejectionTurn);

        return ChatResult.builder()
                .conversationId(pending.getConversationId())
                .reply(REJECTED_MESSAGE)
                .build();
    }

    /**
     * Cubre a la vez "inexistente", "de otro usuario" y "ya resuelta" (confirmada,
     * rechazada o previamente expirada) con el mismo 404: nunca un 403, para no
     * confirmarle a quien pregunta que el id sí existe.
     */
    private PendingAction findPendiente(UserContext userContext, UUID pendingActionId) {
        PendingAction pending = pendingActionRepositoryPort.findByIdAndUser(pendingActionId, userContext.userId())
                .orElseThrow(() -> new PendingActionNotFoundException(
                        "La acción no existe o no pertenece al usuario."));
        if (pending.getEstado() != PendingActionStatus.PENDIENTE) {
            throw new PendingActionNotFoundException("La acción ya fue resuelta.");
        }
        return pending;
    }
}
