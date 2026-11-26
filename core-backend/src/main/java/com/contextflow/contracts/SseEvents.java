package com.contextflow.contracts;

import com.contextflow.contracts.generated.InternalAi;
import com.contextflow.contracts.generated.PublicApi;

public final class SseEvents {
    private SseEvents() {}

    public enum Public {
        ACCEPTED("request.accepted", PublicApi.EventAccepted.class, false),
        DEGRADED("retrieval.degraded", PublicApi.EventDegraded.class, false),
        STARTED("assistant.started", PublicApi.EventStarted.class, false),
        DELTA("assistant.delta", PublicApi.EventDelta.class, false),
        COMPLETED("assistant.completed", PublicApi.EventCompleted.class, true),
        FAILED("request.failed", PublicApi.EventFailed.class, true),
        CANCELLED("request.cancelled", PublicApi.EventCancelled.class, true);

        private final String wireName;
        private final Class<?> payloadType;
        private final boolean terminal;

        Public(String wireName, Class<?> payloadType, boolean terminal) {
            this.wireName = wireName;
            this.payloadType = payloadType;
            this.terminal = terminal;
        }

        public String wireName() {
            return wireName;
        }

        public Class<?> payloadType() {
            return payloadType;
        }

        public boolean terminal() {
            return terminal;
        }
    }

    public enum Internal {
        DISPATCHED("provider.dispatched", InternalAi.ProviderDispatched.class),
        DELTA("assistant.delta", InternalAi.AssistantDelta.class),
        COMPLETED("provider.completed", InternalAi.ProviderCompleted.class),
        FAILED("provider.failed", InternalAi.ProviderFailure.class);

        private final String wireName;
        private final Class<?> payloadType;

        Internal(String wireName, Class<?> payloadType) {
            this.wireName = wireName;
            this.payloadType = payloadType;
        }

        public String wireName() {
            return wireName;
        }

        public Class<?> payloadType() {
            return payloadType;
        }
    }
}
