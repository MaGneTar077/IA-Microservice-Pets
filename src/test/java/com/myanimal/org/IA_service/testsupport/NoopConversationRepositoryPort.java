package com.myanimal.org.IA_service.testsupport;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.myanimal.org.IA_service.domain.model.Conversation;
import com.myanimal.org.IA_service.domain.model.Message;
import com.myanimal.org.IA_service.domain.ports.out.ConversationRepositoryPort;

/**
 * Sustituye a JpaConversationRepositoryAdapter en el perfil "test", que no monta
 * datasource/JPA. Solo existe para que el contexto completo (IaServiceApplicationTests)
 * pueda arrancar sin tocar una base real; nadie hace aserciones sobre su comportamiento.
 */
@Component
@Profile("test")
public class NoopConversationRepositoryPort implements ConversationRepositoryPort {

    @Override
    public Conversation create(UUID userId, String titulo) {
        throw new UnsupportedOperationException("Fake de test: no persiste conversaciones.");
    }

    @Override
    public Optional<Conversation> findByIdAndUser(UUID conversationId, UUID userId) {
        return Optional.empty();
    }

    @Override
    public List<Conversation> findByUser(UUID userId, int limit, int offset) {
        return List.of();
    }

    @Override
    public Message append(UUID conversationId, Message message) {
        throw new UnsupportedOperationException("Fake de test: no persiste mensajes.");
    }

    @Override
    public List<Message> findRecentMessages(UUID conversationId, int limit) {
        return List.of();
    }

    @Override
    public List<Message> findMessages(UUID conversationId, int limit, int offset) {
        return List.of();
    }
}
