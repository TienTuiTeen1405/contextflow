package com.contextflow.context.budget;

import com.contextflow.context.dto.ContentBlockDto;
import com.contextflow.context.dto.ContextSourceItemDto;
import com.contextflow.context.dto.ContinuityTurnDto;
import com.contextflow.context.dto.DegradedComponentDto;
import com.contextflow.context.dto.ExplicitReferenceItemDto;
import com.contextflow.context.dto.HistoricalQuoteDto;
import com.contextflow.context.dto.InstructionItemDto;
import com.contextflow.context.dto.IntelligenceContextDto;
import com.contextflow.context.dto.KnowledgeAndEvidenceDto;
import com.contextflow.context.dto.PromptEnvelopeDto;
import com.contextflow.context.dto.ResponseInstructionsDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Local token estimator applying a conservative 1.15x safety multiplier over UTF-8 byte-based raw
 * token counts so budget enforcement runs strictly in Core without extra Gemini token-count calls.
 */
@Component
@RequiredArgsConstructor
public class LocalTokenEstimator {
    public static final double SAFETY_MULTIPLIER = 1.15d;

    private final ObjectMapper objectMapper;

    public int estimateRawTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int utf8Bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, (utf8Bytes + 3) / 4);
    }

    public int applySafetyMultiplier(int rawTokens) {
        if (rawTokens <= 0) {
            return 0;
        }
        return (int) Math.ceil(rawTokens * SAFETY_MULTIPLIER);
    }

    public int estimateTokens(String text) {
        return applySafetyMultiplier(estimateRawTokens(text));
    }

    public int estimateEnvelopeTokens(PromptEnvelopeDto envelope) {
        return applySafetyMultiplier(estimateEnvelopeRawTokens(envelope));
    }

    public int estimateEnvelopeRawTokens(PromptEnvelopeDto envelope) {
        if (envelope == null) {
            return 0;
        }
        int raw = 0;
        raw += estimateRawTokens(envelope.getPlatformPolicy());
        if (envelope.getCurrentUserRequest() != null) {
            raw += estimateContentBlocksRaw(envelope.getCurrentUserRequest().getContentBlocks());
        }
        raw += estimateIntelligenceRaw(envelope.getIntelligenceContext());
        raw += estimateKnowledgeAndEvidenceRaw(envelope.getKnowledgeAndEvidence());
        raw += estimateResponseInstructionsRaw(envelope.getResponseInstructions());
        if (envelope.getContinuity() != null) {
            for (ContinuityTurnDto turn : envelope.getContinuity()) {
                raw += estimateContinuityTurnRaw(turn);
            }
        }
        return raw;
    }

    public int estimateSourceItemRawTokens(ContextSourceItemDto item) {
        if (item == null) {
            return 0;
        }
        return estimateRawTokens(item.getSourceType())
                + estimateRawTokens(item.getAuthority())
                + estimateRawTokens(item.getContent());
    }

    private int estimateContentBlocksRaw(List<ContentBlockDto> blocks) {
        if (blocks == null) {
            return 0;
        }
        int raw = 0;
        for (ContentBlockDto block : blocks) {
            if (block != null) {
                raw += estimateRawTokens(block.getText());
                raw += estimateRawTokens(block.getQuoteRefId());
            }
        }
        return raw;
    }

    private int estimateIntelligenceRaw(IntelligenceContextDto intelligence) {
        if (intelligence == null) {
            return 0;
        }
        int raw = 0;
        if (intelligence.getBackground() != null) {
            raw += estimateRawTokens(intelligence.getBackground().getProjectInformation());
            raw += estimateRawTokens(intelligence.getBackground().getPersonalProfile());
        }
        if (intelligence.getInstructions() != null) {
            raw += estimateInstructionsRaw(intelligence.getInstructions().getMandatoryRules());
            raw += estimateInstructionsRaw(intelligence.getInstructions().getOverridableRules());
            raw += estimateInstructionsRaw(intelligence.getInstructions().getActiveSkills());
            raw += estimateRawTokens(intelligence.getInstructions().getPersonalInstructions());
        }
        return raw;
    }

    private int estimateInstructionsRaw(List<InstructionItemDto> items) {
        if (items == null) {
            return 0;
        }
        int raw = 0;
        for (InstructionItemDto item : items) {
            if (item != null) {
                raw += estimateRawTokens(item.getContent());
            }
        }
        return raw;
    }

    private int estimateKnowledgeAndEvidenceRaw(KnowledgeAndEvidenceDto ke) {
        if (ke == null) {
            return 0;
        }
        int raw = 0;
        if (ke.getHistory() != null) {
            for (ContextSourceItemDto item : ke.getHistory()) {
                raw += estimateSourceItemRawTokens(item);
            }
        }
        if (ke.getKnowledgeBase() != null) {
            for (ContextSourceItemDto item : ke.getKnowledgeBase()) {
                raw += estimateSourceItemRawTokens(item);
            }
        }
        if (ke.getSourceOfTruth() != null) {
            for (ContextSourceItemDto item : ke.getSourceOfTruth()) {
                raw += estimateSourceItemRawTokens(item);
            }
        }
        if (ke.getExplicitReferences() != null) {
            for (ExplicitReferenceItemDto ref : ke.getExplicitReferences()) {
                if (ref != null) {
                    raw += estimateRawTokens(ref.getKind());
                    raw += estimateRawTokens(ref.getSourceType());
                    raw += estimateRawTokens(ref.getSnapshotText());
                }
            }
        }
        return raw;
    }

    private int estimateResponseInstructionsRaw(ResponseInstructionsDto instructions) {
        if (instructions == null) {
            return 0;
        }
        int raw = 0;
        if (instructions.getIntentContext() != null) {
            raw += estimateRawTokens(instructions.getIntentContext().getPrimaryIntent());
            raw += estimateRawTokens(instructions.getIntentContext().getTimeScope());
            if (instructions.getIntentContext().getResolvedEntities() != null) {
                for (String entity : instructions.getIntentContext().getResolvedEntities()) {
                    raw += estimateRawTokens(entity);
                }
            }
        }
        raw += estimateEnumRawTokens(instructions.getEvidenceSufficiency());
        raw += estimateEnumRawTokens(instructions.getRetrievalStatus());
        if (instructions.getDegradedComponents() != null) {
            for (DegradedComponentDto component : instructions.getDegradedComponents()) {
                if (component != null) {
                    raw += estimateEnumRawTokens(component.getComponent());
                    raw += estimateRawTokens(component.getReasonCode());
                }
            }
        }
        return raw;
    }

    private int estimateEnumRawTokens(Enum<?> value) {
        return value == null ? 0 : estimateRawTokens(value.name());
    }

    private int estimateContinuityTurnRaw(ContinuityTurnDto turn) {
        if (turn == null) {
            return 0;
        }
        int raw = 0;
        if (turn.getUserTextBlocks() != null) {
            for (String text : turn.getUserTextBlocks()) {
                raw += estimateRawTokens(text);
            }
        }
        if (turn.getHistoricalQuotes() != null) {
            for (HistoricalQuoteDto quote : turn.getHistoricalQuotes()) {
                if (quote != null) {
                    raw += estimateRawTokens(quote.getSnapshotText());
                }
            }
        }
        if (turn.getNativeOutputSteps() != null && !turn.getNativeOutputSteps().isEmpty()) {
            try {
                raw +=
                        estimateRawTokens(
                                objectMapper.writeValueAsString(turn.getNativeOutputSteps()));
            } catch (JsonProcessingException ignored) {
                raw += estimateRawTokens(turn.getNativeOutputSteps().toString());
            }
        }
        return raw;
    }
}
