package com.contextflow.identity;

import com.contextflow.common.api.*;
import com.contextflow.contracts.generated.PublicApi.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonalSkillService {
    private final CurrentUser current;
    private final PersonalSkillRepository skills;
    private final UserRepository users;

    public PersonalSkillService(
            CurrentUser current, PersonalSkillRepository skills, UserRepository users) {
        this.current = current;
        this.skills = skills;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public PersonalIntelligenceView intelligence() {
        UserProfile profile = current.profile();
        var text = users.intelligence(profile.id());
        return new PersonalIntelligenceView(
                profile.personalIntelligenceEnabled(),
                text.profile(),
                text.instructions(),
                skills.list(profile.id()));
    }

    @Transactional
    public SkillView create(SkillCreateInput input) {
        UUID user = current.id();
        if (input.sourceTemplateId() != null)
            throw ApiProblem.invalid("Template copying is outside this demo scope.");
        var value =
                new SkillView(
                        UUID.randomUUID(),
                        input.name().trim(),
                        input.description(),
                        input.content(),
                        input.priority() == null ? 100L : input.priority(),
                        input.isEnabled() == null || input.isEnabled());
        skills.insert(user, value);
        return value;
    }

    @Transactional
    public SkillView patch(UUID id, RequestFields<SkillPatchInput> patch) {
        UUID user = current.id();
        var old = skills.find(user, id).orElseThrow(ApiProblem::missing);
        var input = patch.value();
        var value =
                new SkillView(
                        id,
                        patch.has("name") ? input.name().trim() : old.name(),
                        patch.has("description") ? input.description() : old.description(),
                        patch.has("content") ? input.content() : old.content(),
                        patch.has("priority") ? input.priority() : old.priority(),
                        patch.has("isEnabled") ? input.isEnabled() : old.isEnabled());
        skills.update(user, value);
        return value;
    }

    @Transactional
    public void delete(UUID id) {
        if (skills.delete(current.id(), id) != 1) throw ApiProblem.missing();
    }
}
