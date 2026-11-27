package com.contextflow.knowledge.chunking;

import static org.junit.jupiter.api.Assertions.*;

import com.contextflow.knowledge.dto.KnowledgeChunkDraft;
import com.contextflow.knowledge.dto.TurnContentBlockDto;
import com.contextflow.knowledge.dto.TurnIngestionRequest;
import com.contextflow.knowledge.dto.TurnQuoteExclusionDto;
import com.contextflow.knowledge.worker.TokenBucketTpmLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KnowledgeChunkerAndFilterTest {
    private final PreChunkingFilter filter = new PreChunkingFilter();
    private final KnowledgeChunker chunker = new KnowledgeChunker();

    @Test
    void preChunkingFilterExcludesUnmergedSubBranchQuotesAndKeepsNormalQuotes() {
        TurnIngestionRequest req =
                TurnIngestionRequest.builder()
                        .projectId(UUID.randomUUID())
                        .turnId(UUID.randomUUID())
                        .userContentBlocks(
                                List.of(
                                        TurnContentBlockDto.textBlock("So sánh "),
                                        TurnContentBlockDto.quoteRefBlock("q_sub"),
                                        TurnContentBlockDto.textBlock(" và "),
                                        TurnContentBlockDto.quoteRefBlock("q_main")))
                        .quoteSnapshots(
                                List.of(
                                        TurnQuoteExclusionDto.builder()
                                                .quoteRefId("q_sub")
                                                .snapshotText("SECRET_SUB_BRANCH_UNMERGED_TEXT")
                                                .excludeFromAutoKb(true)
                                                .build(),
                                        TurnQuoteExclusionDto.builder()
                                                .quoteRefId("q_main")
                                                .snapshotText("INCLUDED_MAIN_QUOTE_TEXT")
                                                .excludeFromAutoKb(false)
                                                .build()))
                        .assistantText("Kết luận chính thức cho tuyến Main.")
                        .build();

        PreChunkingFilter.FilteredTurnContent result = filter.filter(req);
        assertEquals(1, result.getExcludedQuoteCount());
        assertFalse(result.getProjectedContent().contains("SECRET_SUB_BRANCH_UNMERGED_TEXT"));
        assertTrue(result.getProjectedContent().contains("INCLUDED_MAIN_QUOTE_TEXT"));
        assertTrue(result.getProjectedContent().contains("Kết luận chính thức cho tuyến Main."));
    }

    @Test
    void chunkerSplitsLongContentInto500To800TokenChunksAndExtractsTechnicalTerms() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 18; i++) {
            sb.append("Đoạn kiến trúc số ")
                    .append(i)
                    .append(
                            " mô tả chi tiết quyết định CF-DEC-038 cho bảng embedding_outbox và lớp EmbeddingOutboxWorker gọi /internal/v1/ai/embeddings với cấu hình `output_dimensionality=1536`. ")
                    .append(
                            "Nội dung bổ sung nhằm kiểm chứng việc chia đoạn theo ngưỡng 500-800 tokens không làm đứt gãy ngữ nghĩa tiếng Việt.\n\n");
        }
        sb.append(
                "```sql\nSELECT * FROM contextflow.embedding_outbox WHERE status = 'PENDING';\n```");

        List<KnowledgeChunkDraft> chunks = chunker.chunk(sb.toString());
        assertTrue(chunks.size() >= 2, "Expected multiple chunks for long document");
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunkDraft chunk = chunks.get(i);
            assertEquals(i, chunk.getChunkIndex());
            assertTrue(
                    chunk.getEstimatedTokens() <= KnowledgeChunker.MAX_CHUNK_TOKENS + 50,
                    "Chunk exceeded max token ceiling: " + chunk.getEstimatedTokens());
            assertFalse(chunk.getContent().isBlank());
        }

        List<String> firstTerms = chunks.getFirst().getTechnicalTerms();
        assertTrue(firstTerms.contains("CF-DEC-038"));
        assertTrue(firstTerms.contains("embedding_outbox"));
        assertTrue(firstTerms.contains("EmbeddingOutboxWorker"));
        assertTrue(firstTerms.contains("/internal/v1/ai/embeddings"));
    }

    @Test
    void tokenBucketTpmLimiterThrottlesAndRefillsDeterministically() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-28T00:00:00Z"));
        Clock mutableClock =
                new Clock() {
                    @Override
                    public java.time.ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return now.get();
                    }
                };

        TokenBucketTpmLimiter limiter = new TokenBucketTpmLimiter(1000, mutableClock);
        assertTrue(limiter.tryConsume(700));
        assertFalse(limiter.tryConsume(500));
        assertTrue(limiter.estimateWaitDuration(500).compareTo(Duration.ZERO) > 0);

        // Advance 30s -> refills 500 tokens -> 300 + 500 = 800 available
        now.set(now.get().plusSeconds(30));
        assertTrue(limiter.tryConsume(500));
    }
}
