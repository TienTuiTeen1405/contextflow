package com.contextflow.identity;

import com.contextflow.common.api.RequestFields;
import com.contextflow.contracts.generated.PublicApi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.*;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("demo")
@RequestMapping("/api/v1/me")
public class ProfileController {
    private final ProfileService profiles;
    private final PersonalSkillService skills;
    private final ObjectMapper mapper;
    private final Validator validator;

    public ProfileController(
            ProfileService profiles,
            PersonalSkillService skills,
            ObjectMapper mapper,
            Validator validator) {
        this.profiles = profiles;
        this.skills = skills;
        this.mapper = mapper;
        this.validator = validator;
    }

    @GetMapping
    public UserProfile me() {
        return profiles.get();
    }

    @PatchMapping
    public UserProfile patch(@RequestBody ObjectNode body) {
        return profiles.patch(RequestFields.read(body, ProfilePatch.class, mapper, validator));
    }

    @GetMapping("/personal-intelligence")
    public PersonalIntelligenceView intelligence() {
        return skills.intelligence();
    }

    @PostMapping("/personal-skills")
    @ResponseStatus(HttpStatus.CREATED)
    public SkillView createSkill(@RequestBody ObjectNode body) {
        return skills.create(
                RequestFields.read(body, SkillCreateInput.class, mapper, validator).value());
    }

    @PatchMapping("/personal-skills/{skillId}")
    public SkillView patchSkill(@PathVariable UUID skillId, @RequestBody ObjectNode body) {
        return skills.patch(
                skillId, RequestFields.read(body, SkillPatchInput.class, mapper, validator));
    }

    @DeleteMapping("/personal-skills/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSkill(@PathVariable UUID skillId) {
        skills.delete(skillId);
    }
}
