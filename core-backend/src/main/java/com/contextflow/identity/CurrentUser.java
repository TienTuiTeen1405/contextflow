package com.contextflow.identity;

import com.contextflow.common.api.ApiProblem;
import com.contextflow.contracts.generated.PublicApi.*;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public UserProfile profile() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof UserIdentity identity))
            throw new ApiProblem(ErrorCode.UNAUTHENTICATED, "Authentication is required.");
        UserProfile profile =
                users.find(identity.userId())
                        .orElseThrow(() ->
                                new ApiProblem(ErrorCode.UNAUTHENTICATED, "Account unavailable."));
        if (profile.status() != UserStatus.ACTIVE)
            throw new ApiProblem(ErrorCode.ACCOUNT_DISABLED, "Account is disabled.");
        return profile;
    }

    public UUID id() {
        return profile().id();
    }
}
