package com.contextflow.identity.demo;

import com.contextflow.identity.UserIdentity;
import java.util.UUID;

public final class LocalDemoIdentity {
    public static final UUID USER_ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    public static final String EMAIL = "demo@contextflow.local";
    public static final String DISPLAY_NAME = "Demo User";

    private LocalDemoIdentity() {}

    public static final class DemoPrincipal implements UserIdentity {
        private final UUID userId;

        public DemoPrincipal(UUID userId) {
            this.userId = userId;
        }

        @Override
        public UUID getUserId() {
            return userId;
        }
    }
}
