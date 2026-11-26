package com.contextflow.project;

import com.contextflow.common.api.*;
import com.contextflow.contracts.generated.PublicApi.*;
import com.contextflow.identity.CurrentUser;
import com.contextflow.knowledge.IndexGenerationInitializer;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private final CurrentUser current;
    private final ProjectRepository projects;
    private final IndexGenerationInitializer generations;

    public ProjectService(
            CurrentUser current,
            ProjectRepository projects,
            IndexGenerationInitializer generations) {
        this.current = current;
        this.projects = projects;
        this.generations = generations;
    }

    public ProjectView require(UUID id, boolean write) {
        var value = projects.find(current.id(), id, write).orElseThrow(ApiProblem::missing);
        if (value.status() == ProjectStatus.DELETING)
            throw new ApiProblem(ErrorCode.PROJECT_DELETING, "Project is being deleted.");
        if (value.status() == ProjectStatus.DELETED) throw ApiProblem.missing();
        if (write && value.status() != ProjectStatus.ACTIVE)
            throw ApiProblem.invalid(
                    "Archived workspace writes await TBD-01; this demo is read-only for archived projects.");
        return value;
    }

    @Transactional(readOnly = true)
    public ProjectView get(UUID id) {
        return require(id, false);
    }

    @Transactional(readOnly = true)
    public ProjectsPage list(int page, int size, ProjectWritableStatus status) {
        UUID user = current.id();
        String filter = status == null ? null : status.name();
        long total = projects.count(user, filter);
        return new ProjectsPage(
                projects.list(user, filter, size, (long) page * size),
                (long) page,
                (long) size,
                total,
                (total + size - 1) / size,
                ((long) page + 1) * size < total);
    }

    @Transactional
    public ProjectView create(ProjectCreateInput input) {
        UUID user = current.id();
        if (!input.ruleTemplateIds().isEmpty() || !input.skillTemplateIds().isEmpty())
            throw ApiProblem.invalid("Template copying is outside this demo scope.");
        UUID id = UUID.randomUUID();
        projects.insert(user, id, input.name().trim(), input.description());
        generations.initialize(id);
        return projects.find(user, id, false).orElseThrow();
    }

    @Transactional
    public ProjectView patch(UUID id, RequestFields<ProjectPatchInput> patch) {
        var old = require(id, true);
        if (patch.has("status"))
            throw ApiProblem.invalid("Archive transitions are outside this demo scope.");
        projects.update(
                current.id(),
                id,
                patch.has("name") ? patch.value().name().trim() : old.name(),
                patch.has("description") ? patch.value().description() : old.description());
        return projects.find(current.id(), id, false).orElseThrow();
    }
}
