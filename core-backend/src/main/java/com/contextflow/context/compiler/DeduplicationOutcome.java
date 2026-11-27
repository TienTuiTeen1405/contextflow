package com.contextflow.context.compiler;

import com.contextflow.context.dto.BudgetExplicitRefDto;
import com.contextflow.context.dto.BudgetHistoryItemDto;
import com.contextflow.context.dto.BudgetKnowledgeItemDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeduplicationOutcome {
    @Builder.Default private List<BudgetExplicitRefDto> explicitReferences = new ArrayList<>();

    @Builder.Default private Map<String, String> explicitReferenceAliases = new HashMap<>();

    @Builder.Default private List<BudgetHistoryItemDto> historyItems = new ArrayList<>();

    @Builder.Default private List<BudgetKnowledgeItemDto> knowledgeItems = new ArrayList<>();

    private int droppedExplicitDuplicates;
    private int droppedHistoryDuplicates;
    private int droppedKnowledgeDuplicates;
}
