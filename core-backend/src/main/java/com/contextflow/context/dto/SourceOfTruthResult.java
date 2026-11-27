package com.contextflow.context.dto;

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
public class SourceOfTruthResult {
    @Builder.Default private List<SourceOfTruthRecordDto> records = new ArrayList<>();

    private boolean degraded;
    private String degradedReasonCode;

    public static SourceOfTruthResult empty() {
        return SourceOfTruthResult.builder().build();
    }

    public static SourceOfTruthResult unavailable(String reasonCode) {
        return SourceOfTruthResult.builder().degraded(true).degradedReasonCode(reasonCode).build();
    }
}
