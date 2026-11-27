package com.contextflow.knowledge.worker;

import com.contextflow.knowledge.client.EmbeddingClientException;
import com.contextflow.knowledge.dto.ClaimedOutboxTask;
import com.contextflow.knowledge.dto.EmbeddingBatchRequest;
import com.contextflow.knowledge.dto.EmbeddingBatchResponse;
import com.contextflow.knowledge.dto.OutboxBatchOutcome;
import com.contextflow.knowledge.dto.OutboxStatus;
import com.contextflow.knowledge.dto.ProviderErrorCategory;
import com.contextflow.knowledge.port.EmbeddingClient;
import com.contextflow.knowledge.port.EmbeddingCredentialProvider;
import com.contextflow.knowledge.repository.KnowledgeIngestionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Asynchronous TPM-throttled embedding_outbox worker: claims batches of up to 10 chunks with the
 * same (project_id, index_generation_id) via FOR UPDATE SKIP LOCKED, dispatches to
 * /internal/v1/ai/embeddings, enforces all 5 vector-write guards, and maps all 7 CF-DEC-042 error
 * categories to deterministic outbox transitions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingOutboxWorker {
    private static final String TASK_TYPE_KB_CHUNK = "KB_CHUNK_EMBEDDING";
    private static final int MAX_PROVIDER_BATCH_SIZE = 10;
    private static final long BASE_BACKOFF_SECONDS = 5L;
    private static final long MAX_BACKOFF_SECONDS = 300L;

    private final KnowledgeIngestionRepository repository;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingCredentialProvider credentialProvider;
    private final TokenBucketTpmLimiter tpmLimiter;
    private final KnowledgeOutboxProperties properties;
    private final Clock clock;

    public OutboxBatchOutcome processNextBatch() {
        Instant now = clock.instant();
        repository.reclaimExpiredLeases(now);

        int batchSize = Math.max(1, Math.min(properties.getBatchSize(), MAX_PROVIDER_BATCH_SIZE));
        UUID workerToken = UUID.randomUUID();
        Instant leaseExpiresAt =
                now.plusSeconds(Math.max(10, properties.getLeaseDurationSeconds()));

        List<ClaimedOutboxTask> batch =
                repository.claimPendingBatch(workerToken, now, leaseExpiresAt, batchSize);
        if (batch.isEmpty()) {
            return OutboxBatchOutcome.builder().build();
        }

        int totalBatchTokens =
                batch.stream().mapToInt(task -> Math.max(1, task.getEstimatedTokens())).sum();
        if (!tpmLimiter.tryConsume(totalBatchTokens)) {
            Duration wait = tpmLimiter.estimateWaitDuration(totalBatchTokens);
            Instant retryAt = now.plus(wait);
            for (ClaimedOutboxTask task : batch) {
                repository.releaseThrottledTask(task.getOutboxId(), workerToken, retryAt);
            }
            return OutboxBatchOutcome.builder().claimedCount(batch.size()).throttled(true).build();
        }

        UUID projectId = batch.getFirst().getProjectId();
        Optional<String> credentialOpt = credentialProvider.resolvePlatformCredential(projectId);
        if (credentialOpt.isEmpty() || credentialOpt.get().isBlank()) {
            return handleBatchFailure(
                    batch,
                    workerToken,
                    now,
                    ProviderErrorCategory.CONFIGURATION_BLOCKED,
                    "PLATFORM_CREDENTIAL_MISSING",
                    null);
        }

        EmbeddingBatchRequest request =
                EmbeddingBatchRequest.builder()
                        .correlationId(UUID.randomUUID())
                        .providerCallId(UUID.randomUUID())
                        .taskType(TASK_TYPE_KB_CHUNK)
                        .model(batch.getFirst().getEmbeddingModel())
                        .outputDimensionality(batch.getFirst().getOutputDimensionality())
                        .texts(batch.stream().map(ClaimedOutboxTask::getContent).toList())
                        .build();

        EmbeddingBatchResponse response;
        try {
            response = embeddingClient.embedBatch(request, credentialOpt.get());
        } catch (EmbeddingClientException ex) {
            log.warn(
                    "Embedding batch failed category={} code={} size={}",
                    ex.getErrorCategory(),
                    ex.getErrorCode(),
                    batch.size());
            return handleBatchFailure(
                    batch,
                    workerToken,
                    now,
                    ex.getErrorCategory(),
                    ex.getErrorCode(),
                    ex.getRetryAfterSeconds());
        } catch (Exception ex) {
            log.warn(
                    "Unexpected embedding batch failure exc_type={} size={}",
                    ex.getClass().getSimpleName(),
                    batch.size());
            return handleBatchFailure(
                    batch,
                    workerToken,
                    now,
                    ProviderErrorCategory.UNKNOWN_REQUIRES_REVIEW,
                    "ADAPTER_UNAVAILABLE",
                    null);
        }

        if (response == null
                || response.getVectors() == null
                || response.getVectors().size() != batch.size()) {
            return handleBatchFailure(
                    batch,
                    workerToken,
                    now,
                    ProviderErrorCategory.PERMANENT_INPUT_ERROR,
                    "INVALID_ADAPTER_OUTPUT",
                    null);
        }

        int completed = 0;
        int cancelled = 0;
        for (int i = 0; i < batch.size(); i++) {
            ClaimedOutboxTask task = batch.get(i);
            List<Double> vector = response.getVectors().get(i);
            boolean written = repository.verifyAndWriteChunkEmbedding(task, vector);
            if (written) {
                completed++;
            } else {
                cancelled++;
            }
        }

        return OutboxBatchOutcome.builder()
                .claimedCount(batch.size())
                .completedCount(completed)
                .cancelledCount(cancelled)
                .build();
    }

    private OutboxBatchOutcome handleBatchFailure(
            List<ClaimedOutboxTask> batch,
            UUID workerToken,
            Instant now,
            ProviderErrorCategory rawCategory,
            String errorCode,
            Integer retryAfterSeconds) {
        ProviderErrorCategory category =
                rawCategory != null ? rawCategory : ProviderErrorCategory.UNKNOWN_REQUIRES_REVIEW;
        String safeCode = errorCode != null && !errorCode.isBlank() ? errorCode : category.name();

        int retried = 0;
        int blocked = 0;
        int failed = 0;
        int cancelled = 0;

        for (ClaimedOutboxTask task : batch) {
            OutboxStatus targetStatus = resolveTargetStatus(category, task.getAttemptCount());
            Instant nextRetryAt =
                    targetStatus == OutboxStatus.PENDING
                            ? computeNextRetryAt(now, task.getAttemptCount(), retryAfterSeconds)
                            : now;
            repository.markOutboxFailure(
                    task.getOutboxId(), workerToken, targetStatus, category, safeCode, nextRetryAt);

            switch (targetStatus) {
                case PENDING -> retried++;
                case CONFIGURATION_BLOCKED, HARD_QUOTA_BLOCKED -> blocked++;
                case CANCELLED -> cancelled++;
                default -> failed++;
            }
        }

        return OutboxBatchOutcome.builder()
                .claimedCount(batch.size())
                .retriedCount(retried)
                .blockedCount(blocked)
                .failedCount(failed)
                .cancelledCount(cancelled)
                .build();
    }

    private OutboxStatus resolveTargetStatus(ProviderErrorCategory category, int attemptCount) {
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        return switch (category) {
            case TRANSIENT_RETRYABLE, RATE_LIMIT_RETRYABLE ->
                    attemptCount >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING;
            case HARD_QUOTA_BLOCKED -> OutboxStatus.HARD_QUOTA_BLOCKED;
            case CONFIGURATION_BLOCKED -> OutboxStatus.CONFIGURATION_BLOCKED;
            case SOURCE_INVALIDATED -> OutboxStatus.CANCELLED;
            case PERMANENT_INPUT_ERROR, UNKNOWN_REQUIRES_REVIEW -> OutboxStatus.FAILED;
        };
    }

    private static Instant computeNextRetryAt(
            Instant now, int attemptCount, Integer retryAfterSeconds) {
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return now.plusSeconds(retryAfterSeconds);
        }
        int shift = Math.min(6, Math.max(0, attemptCount - 1));
        long backoff = Math.min(MAX_BACKOFF_SECONDS, BASE_BACKOFF_SECONDS * (1L << shift));
        return now.plusSeconds(backoff);
    }
}
