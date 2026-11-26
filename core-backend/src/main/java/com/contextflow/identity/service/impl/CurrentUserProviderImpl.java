package com.contextflow.identity.service.impl;

import com.contextflow.common.api.ApiProblem;
import com.contextflow.common.api.ErrorCode;
import com.contextflow.identity.UserIdentity;
import com.contextflow.identity.service.CurrentUserProvider;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CurrentUserProviderImpl implements CurrentUserProvider {
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(readOnly = true)
    public UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof UserIdentity identity)) {
            throw new ApiProblem(ErrorCode.UNAUTHENTICATED, "Authentication is required.");
        }
        UUID userId = identity.getUserId();
        List<String> statuses =
                jdbcTemplate.query(
                        "SELECT status FROM contextflow.users WHERE id = ?",
                        (row, index) -> row.getString("status"),
                        userId);
        if (statuses.isEmpty()) {
            throw new ApiProblem(ErrorCode.UNAUTHENTICATED, "Account unavailable.");
        }
        if (!"ACTIVE".equals(statuses.getFirst())) {
            throw new ApiProblem(ErrorCode.ACCOUNT_DISABLED, "Account is disabled.");
        }
        return userId;
    }
}
