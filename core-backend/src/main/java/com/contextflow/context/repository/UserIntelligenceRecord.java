package com.contextflow.context.repository;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserIntelligenceRecord {
    private UUID userId;
    private boolean personalIntelligenceEnabled;
    private String personalProfileText;
    private String personalInstructions;
}
