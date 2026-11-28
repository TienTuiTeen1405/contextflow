package com.contextflow.conversation.repository;

import com.contextflow.conversation.entity.TurnQuoteReference;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TurnQuoteReferenceRepository extends JpaRepository<TurnQuoteReference, UUID> {
    List<TurnQuoteReference> findByProjectIdAndConversationIdAndTurnIdInOrderByCreatedAtAscIdAsc(
            UUID projectId, UUID conversationId, Collection<UUID> turnIds);
}
