package com.contextflow.context.port;

import com.contextflow.context.dto.SourceOfTruthRequest;
import com.contextflow.context.dto.SourceOfTruthResult;

/** Extension port for plugging Source of Truth decision retrieval into Block D. */
public interface SourceOfTruthPort {
    SourceOfTruthResult retrieve(SourceOfTruthRequest request);
}
