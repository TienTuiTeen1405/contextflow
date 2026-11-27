package com.contextflow.knowledge.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunkDraft {
    private int chunkIndex;
    private String content;
    private String searchText;
    @Builder.Default private List<String> technicalTerms = new ArrayList<>();
    private int estimatedTokens;
}
