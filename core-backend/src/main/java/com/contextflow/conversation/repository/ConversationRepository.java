package com.contextflow.conversation.repository;

import com.contextflow.conversation.dto.ConversationStatus;
import com.contextflow.conversation.entity.Conversation;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    Page<Conversation> findByProjectIdAndStatusIn(
            UUID projectId, Collection<ConversationStatus> statuses, Pageable pageable);

    Optional<Conversation> findByIdAndProjectId(UUID id, UUID projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conversation c WHERE c.id = :id AND c.projectId = :projectId")
    Optional<Conversation> findByIdAndProjectIdForUpdate(
            @Param("id") UUID id, @Param("projectId") UUID projectId);
}
