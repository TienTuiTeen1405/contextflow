package com.contextflow.conversation;

import com.contextflow.ai.ContinuityBootstrap;
import com.contextflow.common.api.*;
import com.contextflow.contracts.generated.PublicApi.*;
import com.contextflow.identity.CurrentUser;
import com.contextflow.project.ProjectService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    private final CurrentUser current;
    private final ProjectService projects;
    private final ConversationRepository conversations;
    private final ContinuityBootstrap continuity;

    public ConversationService(
            CurrentUser current,
            ProjectService projects,
            ConversationRepository conversations,
            ContinuityBootstrap continuity) {
        this.current = current;
        this.projects = projects;
        this.conversations = conversations;
        this.continuity = continuity;
    }

    @Transactional(readOnly = true)
    public ConversationsPage list(UUID project, int page, int size, ProjectWritableStatus status) {
        projects.require(project, false);
        UUID user = current.id();
        String filter = status == null ? null : status.name();
        long total = conversations.count(user, project, filter);
        return new ConversationsPage(
                conversations.list(user, project, filter, size, (long) page * size),
                (long) page,
                (long) size,
                total,
                (total + size - 1) / size,
                ((long) page + 1) * size < total);
    }

    @Transactional
    public ConversationView create(UUID project, TitleCreateInput input) {
        projects.require(project, true);
        UUID id = UUID.randomUUID();
        UUID stage = UUID.randomUUID();
        conversations.create(project, id, input.title().trim(), stage);
        continuity.initializeDemoMain(project, id, stage);
        return conversations.find(current.id(), project, id, false).orElseThrow();
    }

    @Transactional
    public ConversationView patch(
            UUID project, UUID id, RequestFields<ConversationPatchInput> patch) {
        projects.require(project, true);
        UUID user = current.id();
        var old = conversations.find(user, project, id, true).orElseThrow(ApiProblem::missing);
        if (old.status() != ConversationStatus.ACTIVE || patch.has("status"))
            throw ApiProblem.invalid(
                    "Archive/locked conversation changes are outside this demo scope.");
        conversations.rename(user, project, id, patch.value().title().trim());
        return conversations.find(user, project, id, false).orElseThrow();
    }
}
