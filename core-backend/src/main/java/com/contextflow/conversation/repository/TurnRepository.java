package com.contextflow.conversation.repository;

import com.contextflow.conversation.entity.Turn;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TurnRepository extends JpaRepository<Turn, UUID> {
    /** Committed Main-route Turns newer than {@code afterSequence}, oldest first. */
    @Query(
            """
            SELECT t FROM Turn t
            WHERE t.projectId = :projectId
              AND t.mainStageId = :mainStageId
              AND t.subBranchId IS NULL
              AND t.sequenceNo > :afterSequence
            ORDER BY t.sequenceNo ASC
            """)
    List<Turn> findMainTurnsAfter(
            @Param("projectId") UUID projectId,
            @Param("mainStageId") UUID mainStageId,
            @Param("afterSequence") long afterSequence,
            Pageable pageable);

    /** Committed Main-route Turns older than {@code beforeSequence}, newest first. */
    @Query(
            """
            SELECT t FROM Turn t
            WHERE t.projectId = :projectId
              AND t.mainStageId = :mainStageId
              AND t.subBranchId IS NULL
              AND t.sequenceNo < :beforeSequence
            ORDER BY t.sequenceNo DESC
            """)
    List<Turn> findMainTurnsBefore(
            @Param("projectId") UUID projectId,
            @Param("mainStageId") UUID mainStageId,
            @Param("beforeSequence") long beforeSequence,
            Pageable pageable);
}
