package com.contextflow.credential.service;

import com.contextflow.credential.dto.PrivacyAcknowledgementResponse;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PrivacyAcknowledgementResult {
    private final PrivacyAcknowledgementResponse receipt;
    private final boolean created;
}
