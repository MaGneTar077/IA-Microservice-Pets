package com.myanimal.org.IA_service.domain.ports.in;

import java.util.List;
import java.util.UUID;

import com.myanimal.org.IA_service.application.dto.ConversationSummaryDto;
import com.myanimal.org.IA_service.application.dto.MessageDto;

public interface ConversationQueryUseCase {

    List<ConversationSummaryDto> listConversations(UUID userId, int limit, int offset);

    List<MessageDto> listMessages(UUID userId, UUID conversationId, int limit, int offset);
}
