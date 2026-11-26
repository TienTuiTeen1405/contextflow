package com.contextflow.identity.demo;

import com.contextflow.identity.UserIdentity;
import java.util.UUID;

public final class DemoIdentity {
    public static final UUID USER_ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    public static final String EMAIL = "demo@contextflow.local";

    public record Principal(UUID userId) implements UserIdentity {}

    private DemoIdentity() {}
}
