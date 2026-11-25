-- Source: BranOS-03-5, page 7536718, version 2 (2026-11-25), section 4.
-- https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536718
-- ContextFlow V6 — V005: AI Jobs, Technical Steps, Deletion Sessions, Exports & Operation Receipts
SET LOCAL search_path TO contextflow, public;

CREATE TABLE ai_jobs (
    id uuid PRIMARY KEY,
    project_id uuid REFERENCES projects(id) ON DELETE CASCADE,
    initiated_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    correlation_id uuid NOT NULL,
    job_type text NOT NULL CHECK (job_type IN (
        'MAIN_STAGE_CONDENSATION', 'CONVERSATION_CONDENSATION',
        'SUB_BRANCH_CHERRY_PICK_CONDENSE', 'SUB_BRANCH_CONDENSE_ALL',
        'CHAPTER_TOPIC_DETECTION', 'CHAPTER_SUMMARY_GENERATION',
        'INDEX_GENERATION_REBUILD', 'PROJECT_DELETION_CLEANUP', 'CREDENTIAL_KEK_ROTATION'
    )),
    target_scope_type text,
    target_scope_id uuid,
    status text NOT NULL CHECK (status IN (
        'PENDING', 'PROCESSING', 'REVIEW_REQUIRED', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    result_payload jsonb,
    source_fingerprint bytea CHECK (source_fingerprint IS NULL OR octet_length(source_fingerprint) = 32),
    worker_token uuid,
    lease_expires_at timestamptz,
    attempt_number integer NOT NULL DEFAULT 0 CHECK (attempt_number >= 0),
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE (project_id, id),
    CHECK ((worker_token IS NULL) = (lease_expires_at IS NULL))
);
CREATE INDEX ai_jobs_admin_monitor_idx ON ai_jobs (status, created_at DESC);
CREATE INDEX ai_jobs_project_idx ON ai_jobs (project_id, job_type, created_at DESC);

CREATE TABLE ai_job_provider_steps (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    job_id uuid NOT NULL,
    source_scope_type text NOT NULL CHECK (source_scope_type IN (
        'TURN', 'SUB_BRANCH', 'MAIN_STAGE', 'CONVERSATION', 'PROJECT'
    )),
    source_scope_id uuid NOT NULL,
    native_output_steps pg_catalog.json NOT NULL CHECK (json_typeof(native_output_steps) = 'array'),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (project_id, job_id) REFERENCES ai_jobs(project_id, id) ON DELETE CASCADE
);
CREATE INDEX ai_job_steps_by_source ON ai_job_provider_steps (project_id, source_scope_type, source_scope_id);

CREATE TABLE deletion_sessions (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    initiated_by_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    target_type text NOT NULL CHECK (target_type IN ('MAIN_STAGE', 'CONVERSATION', 'PROJECT')),
    target_id uuid NOT NULL,
    status text NOT NULL CHECK (status IN (
        'PREPARING', 'READY_FOR_CONFIRM', 'EXECUTING', 'COMPLETED', 'ABORTED', 'CANCELLED'
    )),
    previous_conversation_status text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE (project_id, id)
);

CREATE TABLE deletion_stage_items (
    id uuid PRIMARY KEY,
    deletion_session_id uuid NOT NULL,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    main_stage_id uuid NOT NULL,
    previous_stage_status text NOT NULL CHECK (previous_stage_status IN ('ACTIVE', 'LOCKED_READONLY')),
    stage_status text NOT NULL CHECK (stage_status IN (
        'PENDING', 'PROCESSING', 'REVIEW_REQUIRED', 'SAVED', 'FAILED', 'SKIPPED'
    )),
    skip_warning_acknowledged boolean NOT NULL DEFAULT false,
    condensation_job_id uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (deletion_session_id, main_stage_id),
    FOREIGN KEY (project_id, deletion_session_id)
        REFERENCES deletion_sessions(project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, condensation_job_id)
        REFERENCES ai_jobs(project_id, id) ON DELETE SET NULL (condensation_job_id),
    CHECK (stage_status <> 'SKIPPED' OR skip_warning_acknowledged)
);

CREATE TABLE export_jobs (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id uuid REFERENCES projects(id) ON DELETE CASCADE,
    conversation_id uuid,
    export_scope text NOT NULL CHECK (export_scope IN (
        'PROJECT_ARCHIVE', 'CONVERSATION', 'PERSONAL_INTELLIGENCE', 'PROJECT_CONFIGURATION'
    )),
    export_format text NOT NULL CHECK (export_format IN ('MARKDOWN', 'JSON')),
    status text NOT NULL CHECK (status IN (
        'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED', 'PURGED'
    )),
    storage_object_key text,
    file_size_bytes bigint CHECK (file_size_bytes IS NULL OR file_size_bytes >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    expires_at timestamptz,
    CHECK ((export_scope = 'PERSONAL_INTELLIGENCE') = (project_id IS NULL)),
    CHECK ((export_scope = 'CONVERSATION') = (conversation_id IS NOT NULL))
);
CREATE INDEX export_jobs_project_idx ON export_jobs (project_id, status);

CREATE TABLE operation_receipts (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id uuid REFERENCES projects(id) ON DELETE CASCADE,
    operation_scope text NOT NULL,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    payload_hash bytea NOT NULL CHECK (octet_length(payload_hash) = 32),
    response_status integer NOT NULL CHECK (response_status BETWEEN 200 AND 299),
    result_ref jsonb NOT NULL CHECK (jsonb_typeof(result_ref) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    UNIQUE (user_id, operation_scope, idempotency_key),
    CHECK (expires_at > created_at)
);
CREATE INDEX operation_receipts_expiry ON operation_receipts (expires_at);
