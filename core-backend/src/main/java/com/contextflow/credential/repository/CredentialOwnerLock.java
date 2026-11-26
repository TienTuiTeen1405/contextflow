package com.contextflow.credential.repository;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CredentialOwnerLock {
    private final JdbcTemplate jdbcTemplate;

    public Optional<String> lockAccountStatus(UUID userId) {
        // Lock the owner even when this account has never had a credential.
        return jdbcTemplate
                .queryForList(
                        "SELECT status FROM contextflow.users WHERE id = ? FOR NO KEY UPDATE",
                        String.class,
                        userId)
                .stream()
                .findFirst();
    }
}
