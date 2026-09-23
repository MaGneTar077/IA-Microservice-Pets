package com.myanimal.org.IA_service.infrastructure.adapters.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageJpaRepository extends JpaRepository<MessageEntity, UUID> {

    @Query(value = "select * from ai_message where conversation_id = :conversationId "
            + "order by created_at desc limit :limit", nativeQuery = true)
    List<MessageEntity> findRecentByConversationId(@Param("conversationId") UUID conversationId,
            @Param("limit") int limit);
}
