package com.myanimal.org.IA_service.domain.ports.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.myanimal.org.IA_service.domain.model.Conversation;
import com.myanimal.org.IA_service.domain.model.Message;

public interface ConversationRepositoryPort {

    Conversation create(UUID userId, String titulo);

    Optional<Conversation> findByIdAndUser(UUID conversationId, UUID userId);

    List<Conversation> findByUser(UUID userId, int limit, int offset);

    Message append(UUID conversationId, Message message);

    List<Message> findRecentMessages(UUID conversationId, int limit);

    // Paginación cronológica ascendente para GET /conversations/{id}/messages (parte D):
    // findRecentMessages solo cubre "los últimos N para mandarle a Gemini", no un offset
    // arbitrario para listar. No estaba en el contrato original de la parte B.
    List<Message> findMessages(UUID conversationId, int limit, int offset);
}
