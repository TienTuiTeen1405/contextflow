package com.contextflow.conversation.repository;

import com.contextflow.conversation.entity.TurnMainReference;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TurnMainReferenceRepository extends JpaRepository<TurnMainReference, UUID> {
    List<TurnMainReference> findByProjectIdAndConversationIdAndTurnIdInOrderByCreatedAtAscIdAsc(
            UUID projectId, UUID conversationId, Collection<UUID> turnIds);
}
