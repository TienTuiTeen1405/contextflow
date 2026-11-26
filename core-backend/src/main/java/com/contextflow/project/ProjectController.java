package com.contextflow.project;

import com.contextflow.common.api.RequestFields;
import com.contextflow.contracts.generated.PublicApi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("demo")
@Validated
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectService projects;
    private final ObjectMapper mapper;
    private final Validator validator;

    public ProjectController(ProjectService projects, ObjectMapper mapper, Validator validator) {
        this.projects = projects;
        this.mapper = mapper;
        this.validator = validator;
    }

    @GetMapping
    public ProjectsPage list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) ProjectWritableStatus status) {
        return projects.list(page, size, status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView create(@RequestBody ObjectNode body) {
        return projects.create(
                RequestFields.read(body, ProjectCreateInput.class, mapper, validator).value());
    }

    @GetMapping("/{projectId}")
    public ProjectView get(@PathVariable UUID projectId) {
        return projects.get(projectId);
    }

    @PatchMapping("/{projectId}")
    public ProjectView patch(@PathVariable UUID projectId, @RequestBody ObjectNode body) {
        return projects.patch(
                projectId, RequestFields.read(body, ProjectPatchInput.class, mapper, validator));
    }
}
