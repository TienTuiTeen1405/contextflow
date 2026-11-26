package com.contextflow.identity;

import com.contextflow.common.api.RequestFields;
import com.contextflow.contracts.generated.PublicApi.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private final CurrentUser current;
    private final UserRepository users;

    public ProfileService(CurrentUser current, UserRepository users) {
        this.current = current;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserProfile get() {
        return current.profile();
    }

    @Transactional
    public UserProfile patch(RequestFields<ProfilePatch> patch) {
        UserProfile old = current.profile();
        users.update(
                old.id(),
                patch.has("displayName") ? patch.value().displayName().trim() : null,
                patch.has("themePreference") ? patch.value().themePreference() : null);
        return users.find(old.id()).orElseThrow();
    }
}
