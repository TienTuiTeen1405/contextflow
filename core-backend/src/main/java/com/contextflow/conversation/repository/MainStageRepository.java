package com.contextflow.conversation.repository;

import com.contextflow.conversation.entity.MainStage;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MainStageRepository extends JpaRepository<MainStage, UUID> {
    Optional<MainStage> findFirstByProjectIdAndConversationIdOrderByStageOrderDesc(
            UUID projectId, UUID conversationId);

    /** Current Main (highest {@code stage_order}) of each given conversation, in one query. */
    @Query(
            """
            SELECT ms FROM MainStage ms
            WHERE ms.projectId = :projectId
              AND ms.conversationId IN :conversationIds
              AND ms.stageOrder = (
                  SELECT MAX(latest.stageOrder) FROM MainStage latest
                  WHERE latest.projectId = ms.projectId
                    AND latest.conversationId = ms.conversationId)
            """)
    List<MainStage> findLatestByProjectIdAndConversationIdIn(
            @Param("projectId") UUID projectId,
            @Param("conversationIds") Collection<UUID> conversationIds);
}
