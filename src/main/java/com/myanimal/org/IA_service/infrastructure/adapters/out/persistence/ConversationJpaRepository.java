package com.myanimal.org.IA_service.infrastructure.adapters.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationJpaRepository extends JpaRepository<ConversationEntity, UUID> {

    Optional<ConversationEntity> findByIdAndUserId(UUID id, UUID userId);

    @Query(value = "select * from ai_conversation where user_id = :userId "
            + "order by updated_at desc limit :limit offset :offset", nativeQuery = true)
    List<ConversationEntity> findByUserId(@Param("userId") UUID userId, @Param("limit") int limit,
            @Param("offset") int offset);
}
