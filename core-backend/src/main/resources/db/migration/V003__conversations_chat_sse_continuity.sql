-- Source: BranOS-03-5, page 7536718, version 2 (2026-11-25), section 4.
-- https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536718
-- ContextFlow V6 — V003: Two-Tier Conversations, Chapters, Chat SSE, Continuity, Usage & Audits
SET LOCAL search_path TO contextflow, public;

CREATE TABLE conversations (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title text NOT NULL CHECK (length(btrim(title)) > 0),
    status text NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ARCHIVED', 'LOCKED_READONLY', 'DELETING')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, id)
);
CREATE INDEX conversations_project_idx ON conversations (project_id, status, updated_at DESC);

CREATE TABLE main_stages (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    parent_stage_id uuid,
    ancestor_stage_ids uuid[] NOT NULL DEFAULT '{}'::uuid[],
    stage_order integer NOT NULL CHECK (stage_order > 0),
    title text NOT NULL CHECK (length(btrim(title)) > 0),
    status text NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'LOCKED_READONLY', 'DELETION_PREPARING', 'DELETING')),
    previous_status text CHECK (previous_status IS NULL OR previous_status IN ('ACTIVE', 'LOCKED_READONLY')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, conversation_id, id),
    FOREIGN KEY (project_id, conversation_id)
        REFERENCES conversations(project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, conversation_id, parent_stage_id)
        REFERENCES main_stages(project_id, conversation_id, id)
        ON DELETE SET NULL (parent_stage_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK (parent_stage_id IS DISTINCT FROM id)
);
CREATE UNIQUE INDEX one_stage_order_per_conversation ON main_stages (conversation_id, stage_order);
CREATE INDEX stage_parent_lookup ON main_stages (project_id, conversation_id, parent_stage_id);

CREATE TABLE chapters (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    main_stage_id uuid NOT NULL,
    title text NOT NULL CHECK (length(btrim(title)) > 0),
    display_order integer NOT NULL CHECK (display_order > 0),
    start_sequence_no bigint CHECK (start_sequence_no IS NULL OR start_sequence_no > 0),
    end_sequence_no bigint,
    source_revision bigint NOT NULL DEFAULT 1 CHECK (source_revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((start_sequence_no IS NULL) = (end_sequence_no IS NULL)),
    CHECK (end_sequence_no IS NULL OR end_sequence_no >= start_sequence_no),
    UNIQUE (project_id, conversation_id, main_stage_id, id),
    FOREIGN KEY (project_id, conversation_id, main_stage_id)
        REFERENCES main_stages(project_id, conversation_id, id) ON DELETE CASCADE
);

CREATE TABLE chapter_summaries (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    main_stage_id uuid NOT NULL,
    chapter_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    summary_text text NOT NULL,
    approval_status text NOT NULL CHECK (approval_status IN (
        'DRAFT', 'USER_APPROVED', 'USER_EDITED', 'SUPERSEDED'
    )),
    source_revision_snapshot bigint NOT NULL CHECK (source_revision_snapshot > 0),
    approved_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    approved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (chapter_id, version),
    FOREIGN KEY (project_id, conversation_id, main_stage_id, chapter_id)
        REFERENCES chapters(project_id, conversation_id, main_stage_id, id) ON DELETE CASCADE,
    CHECK (approval_status NOT IN ('USER_APPROVED', 'USER_EDITED')
           OR (approved_at IS NOT NULL AND approved_by_user_id IS NOT NULL))
);
CREATE UNIQUE INDEX one_active_approved_summary_per_chapter
    ON chapter_summaries (chapter_id)
    WHERE approval_status IN ('USER_APPROVED', 'USER_EDITED');

CREATE TABLE sub_branches (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    main_stage_id uuid NOT NULL,
    anchor_turn_id uuid NOT NULL,
    title text NOT NULL CHECK (length(btrim(title)) > 0),
    status text NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ARCHIVED', 'LOCKED_READONLY', 'DELETING')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, conversation_id, main_stage_id, id),
    FOREIGN KEY (project_id, conversation_id, main_stage_id)
        REFERENCES main_stages(project_id, conversation_id, id) ON DELETE CASCADE
);

CREATE TABLE continuity_epochs (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    main_stage_id uuid NOT NULL,
    sub_branch_id uuid,
    route_kind text NOT NULL CHECK (route_kind IN ('MAIN', 'SUB_BRANCH')),
    route_id uuid NOT NULL,
    epoch_no bigint NOT NULL CHECK (epoch_no > 0),
    status text NOT NULL CHECK (status IN ('ACTIVE', 'INVALIDATED')),
    invalidation_reason text,
    provider text NOT NULL,
    api_interface text NOT NULL,
    model text NOT NULL,
    compatibility_key bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (route_kind = CASE WHEN sub_branch_id IS NULL THEN 'MAIN' ELSE 'SUB_BRANCH' END),
    CHECK (route_id = coalesce(sub_branch_id, main_stage_id)),
    UNIQUE (project_id, conversation_id, id),
    UNIQUE (project_id, conversation_id, main_stage_id, id),
    UNIQUE NULLS NOT DISTINCT (main_stage_id, sub_branch_id, epoch_no),
    UNIQUE (project_id, conversation_id, main_stage_id, route_kind, route_id, id),
    FOREIGN KEY (project_id, conversation_id, main_stage_id)
        REFERENCES main_stages(project_id, conversation_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, conversation_id, main_stage_id, sub_branch_id)
        REFERENCES sub_branches(project_id, conversation_id, main_stage_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX one_active_epoch_per_main
    ON continuity_epochs (main_stage_id) WHERE sub_branch_id IS NULL AND status = 'ACTIVE';
CREATE UNIQUE INDEX one_active_epoch_per_branch
    ON continuity_epochs (sub_branch_id) WHERE sub_branch_id IS NOT NULL AND status = 'ACTIVE';

CREATE TABLE ai_requests (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    project_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    main_stage_id uuid NOT NULL,
    sub_branch_id uuid,
    route_kind text NOT NULL CHECK (route_kind IN ('MAIN', 'SUB_BRANCH')),
    route_id uuid NOT NULL,
    epoch_id uuid,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    payload_hash bytea NOT NULL CHECK (octet_length(payload_hash) = 32),
    correlation_id uuid NOT NULL UNIQUE,
    status text NOT NULL CHECK (status IN ('ACCEPTED', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    queued_input jsonb CHECK (queued_input IS NULL OR jsonb_typeof(queued_input) = 'object'),
    worker_token uuid,
    lease_expires_at timestamptz,
    last_event_id bigint NOT NULL DEFAULT 0 CHECK (last_event_id >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    error_code text,
    CHECK (route_kind = CASE WHEN sub_branch_id IS NULL THEN 'MAIN' ELSE 'SUB_BRANCH' END),
    CHECK (route_id = coalesce(sub_branch_id, main_stage_id)),
    UNIQUE (user_id, idempotency_key),
    UNIQUE (project_id, conversation_id, main_stage_id, id),
    FOREIGN KEY (project_id, conversation_id, main_stage_id)
        REFERENCES main_stages(project_id, conversation_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, conversation_id, main_stage_id, sub_branch_id)
        REFERENCES sub_branches(project_id, conversation_id, main_stage_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, conversation_id, main_stage_id, route_kind, route_id, epoch_id)
        REFERENCES continuity_epochs(project_id, conversation_id, main_stage_id, route_kind, route_id, id)
        ON DELETE SET NULL (epoch_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK (status NOT IN ('ACCEPTED', 'IN_PROGRESS') OR epoch_id IS NOT NULL),
    CHECK ((status = 'IN_PROGRESS') = (worker_token IS NOT NULL)),
    CHECK ((worker_token IS NULL) = (lease_expires_at IS NULL)),
    CHECK ((status IN ('ACCEPTED', 'IN_PROGRESS')) = (finished_at IS NULL)),
    CHECK ((status IN ('ACCEPTED', 'IN_PROGRESS')) = (queued_input IS NOT NULL))
);
CREATE UNIQUE INDEX one_open_chat_per_main
    ON ai_requests (main_stage_id) WHERE sub_branch_id IS NULL AND status IN ('ACCEPTED', 'IN_PROGRESS');
CREATE UNIQUE INDEX one_open_chat_per_branch
    ON ai_requests (sub_branch_id) WHERE sub_branch_id IS NOT NULL AND status IN ('ACCEPTED', 'IN_PROGRESS');
CREATE INDEX ai_requests_pending ON ai_requests (created_at, id) WHERE status = 'ACCEPTED';
CREATE INDEX ai_requests_active_lease ON ai_requests (lease_expires_at) WHERE status = 'IN_PROGRESS';

CREATE FUNCTION contextflow.fill_chat_route()
RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    NEW.route_kind := CASE WHEN NEW.sub_branch_id IS NULL THEN 'MAIN' ELSE 'SUB_BRANCH' END;
    NEW.route_id := coalesce(NEW.sub_branch_id, NEW.main_stage_id);
    RETURN NEW;
END;
$fn$;

CREATE TRIGGER trg_epoch_route BEFORE INSERT OR UPDATE ON continuity_epochs
FOR EACH ROW EXECUTE FUNCTION contextflow.fill_chat_route();

CREATE TRIGGER trg_request_route BEFORE INSERT OR UPDATE ON ai_requests
FOR EACH ROW EXECUTE FUNCTION contextflow.fill_chat_route();

CREATE TABLE ai_request_events (
    request_id uuid NOT NULL REFERENCES ai_requests(id) ON DELETE CASCADE,
    event_id bigint NOT NULL CHECK (event_id > 0),
    event_type text NOT NULL CHECK (event_type IN (
        'request.accepted', 'assistant.started', 'assistant.delta', 'retrieval.degraded',
        'assistant.completed', 'request.failed', 'request.cancelled'
    )),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (request_id, event_id)
);
CREATE UNIQUE INDEX one_terminal_event_per_request
    ON ai_request_events (request_id)
    WHERE event_type IN ('assistant.completed', 'request.failed', 'request.cancelled');

CREATE TABLE turns (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    main_stage_id uuid NOT NULL,
    sub_branch_id uuid,
    chapter_id uuid,
    request_id uuid NOT NULL UNIQUE,
    sequence_no bigint NOT NULL CHECK (sequence_no > 0),
    source_revision bigint NOT NULL DEFAULT 1 CHECK (source_revision > 0),
    user_content_blocks jsonb NOT NULL
        CHECK (jsonb_typeof(user_content_blocks) = 'array' AND jsonb_array_length(user_content_blocks) > 0),
    assistant_text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, conversation_id, id),
    UNIQUE (project_id, conversation_id, main_stage_id, id),
    UNIQUE NULLS NOT DISTINCT (main_stage_id, sub_branch_id, sequence_no),
    FOREIGN KEY (project_id, conversation_id, main_stage_id)
        REFERENCES main_stages(project_id, conversation_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, conversation_id, main_stage_id, sub_branch_id)
        REFERENCES sub_branches(project_id, conversation_id, main_stage_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, conversation_id, main_stage_id, chapter_id)
        REFERENCES chapters(project_id, conversation_id, main_stage_id, id)
        ON DELETE SET NULL (chapter_id),
    FOREIGN KEY (project_id, conversation_id, main_stage_id, request_id)
        REFERENCES ai_requests(project_id, conversation_id, main_stage_id, id)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX turns_route_sequence ON turns (project_id, main_stage_id, sub_branch_id, sequence_no);

ALTER TABLE sub_branches
    ADD FOREIGN KEY (project_id, conversation_id, main_stage_id, anchor_turn_id)
    REFERENCES turns(project_id, conversation_id, main_stage_id, id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE turn_main_references (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    turn_id uuid NOT NULL,
    granularity text NOT NULL CHECK (granularity IN ('STAGE', 'CHAPTER', 'TURN')),
    target_stage_id uuid NOT NULL,
    target_chapter_id uuid,
    target_turn_id uuid,
    display_label text NOT NULL,
    link_status text NOT NULL DEFAULT 'AVAILABLE' CHECK (link_status IN ('AVAILABLE', 'UNAVAILABLE')),
    tombstone_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (project_id, conversation_id, turn_id)
        REFERENCES turns(project_id, conversation_id, id) ON DELETE CASCADE,
    CHECK ((granularity = 'CHAPTER') = (target_chapter_id IS NOT NULL)),
    CHECK ((granularity = 'TURN') = (target_turn_id IS NOT NULL))
);
CREATE INDEX turn_main_refs_by_turn ON turn_main_references (turn_id);
CREATE INDEX turn_main_refs_by_target ON turn_main_references (project_id, conversation_id, target_stage_id);

CREATE TABLE turn_quote_references (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    turn_id uuid NOT NULL,
    source_kind text NOT NULL CHECK (source_kind IN (
        'MAIN_TURN', 'SUB_BRANCH_TURN', 'KB_ITEM', 'SOT_RECORD', 'CHAPTER_SUMMARY'
    )),
    source_id uuid NOT NULL,
    source_revision text NOT NULL,
    locator jsonb NOT NULL CHECK (jsonb_typeof(locator) = 'object'),
    content_hash bytea NOT NULL CHECK (octet_length(content_hash) = 32),
    snapshot_text text NOT NULL,
    exclude_from_auto_kb boolean NOT NULL DEFAULT false,
    link_status text NOT NULL DEFAULT 'AVAILABLE' CHECK (link_status IN ('AVAILABLE', 'UNAVAILABLE')),
    tombstone_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (project_id, conversation_id, turn_id)
        REFERENCES turns(project_id, conversation_id, id) ON DELETE CASCADE
);
CREATE INDEX turn_quotes_by_target ON turn_quote_references (turn_id);
CREATE INDEX turn_quotes_by_source ON turn_quote_references (project_id, source_kind, source_id);

CREATE TABLE continuity_turn_entries (
    project_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    epoch_id uuid NOT NULL,
    ordinal bigint NOT NULL CHECK (ordinal > 0),
    turn_id uuid NOT NULL,
    native_output_steps pg_catalog.json NOT NULL CHECK (json_typeof(native_output_steps) = 'array'),
    PRIMARY KEY (epoch_id, ordinal),
    UNIQUE (epoch_id, turn_id),
    FOREIGN KEY (project_id, conversation_id, epoch_id)
        REFERENCES continuity_epochs(project_id, conversation_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, conversation_id, turn_id)
        REFERENCES turns(project_id, conversation_id, id) ON DELETE CASCADE
);

CREATE TABLE ai_usage_records (
    provider_call_id uuid PRIMARY KEY,
    correlation_id uuid NOT NULL,
    contextflow_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    contextflow_project_id uuid REFERENCES projects(id) ON DELETE CASCADE,
    source_conversation_id uuid,
    source_turn_id uuid,
    job_id uuid,
    source_knowledge_item_id uuid,
    source_chunk_id uuid,
    chunk_batch_id uuid,
    index_generation_id uuid,
    task_type text NOT NULL,
    provider text NOT NULL,
    api_interface text NOT NULL,
    model text NOT NULL,
    provider_request_id text,
    credential_id uuid NOT NULL,
    credential_version bigint NOT NULL CHECK (credential_version > 0),
    owner_scope text NOT NULL CHECK (owner_scope IN ('USER', 'PLATFORM')),
    credential_tier text NOT NULL CHECK (credential_tier IN ('FREE', 'PAID', 'UNKNOWN')),
    google_cloud_project_ref text,
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    occurred_at timestamptz NOT NULL,
    accounted_input_tokens bigint NOT NULL DEFAULT 0 CHECK (accounted_input_tokens >= 0),
    accounted_output_tokens bigint NOT NULL DEFAULT 0 CHECK (accounted_output_tokens >= 0),
    request_counted boolean NOT NULL DEFAULT false,
    completed_at timestamptz,
    execution_status text NOT NULL CHECK (execution_status IN (
        'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    error_code text,
    usage_status text NOT NULL CHECK (usage_status IN ('AVAILABLE', 'PARTIAL', 'UNAVAILABLE')),
    usage jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(usage) = 'object'),
    CHECK (completed_at IS NULL OR completed_at >= occurred_at)
);
CREATE INDEX ai_usage_by_project ON ai_usage_records (contextflow_project_id, occurred_at DESC);
CREATE INDEX ai_usage_by_correlation ON ai_usage_records (correlation_id);

CREATE TABLE ai_execution_audits (
    id uuid PRIMARY KEY,
    correlation_id uuid NOT NULL,
    provider_call_id uuid,
    contextflow_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    contextflow_project_id uuid REFERENCES projects(id) ON DELETE CASCADE,
    source_conversation_id uuid,
    source_turn_id uuid,
    job_id uuid,
    prompt_envelope_version text NOT NULL,
    adapter_version text NOT NULL,
    effective_privacy_tier text NOT NULL CHECK (effective_privacy_tier IN ('FREE', 'PAID', 'UNKNOWN', 'NOT_DISPATCHED')),
    intent_context jsonb,
    evidence_sufficiency text CHECK (evidence_sufficiency IS NULL OR evidence_sufficiency IN ('SUFFICIENT', 'PARTIAL', 'INSUFFICIENT')),
    retrieval_status text CHECK (retrieval_status IS NULL OR retrieval_status IN ('COMPLETE', 'PARTIAL', 'UNAVAILABLE')),
    estimated_input_tokens integer,
    pruning_applied boolean NOT NULL DEFAULT false,
    selected_source_ids jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(selected_source_ids) = 'object'),
    latency_ms integer,
    execution_status text NOT NULL,
    normalized_error_code text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ai_audits_by_project ON ai_execution_audits (contextflow_project_id, created_at DESC);
