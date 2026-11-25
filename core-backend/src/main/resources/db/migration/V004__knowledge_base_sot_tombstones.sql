-- Source: BranOS-03-5, page 7536718, version 2 (2026-11-25), section 4.
-- https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536718
-- ContextFlow V6 — V004: Tombstones, Project KB, pgvector HNSW, FTS cf_vi GIN, Outbox, SoT & Context Box
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

CREATE TEXT SEARCH CONFIGURATION public.cf_vi (COPY = pg_catalog.simple);
ALTER TEXT SEARCH CONFIGURATION public.cf_vi
    ALTER MAPPING FOR hword, hword_part, word
    WITH public.unaccent, pg_catalog.simple;

SET LOCAL search_path TO contextflow, public;

CREATE TABLE tombstones (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    object_type text NOT NULL CHECK (object_type IN (
        'CONVERSATION', 'MAIN_STAGE', 'CHAPTER', 'TURN', 'SUB_BRANCH', 'KB_ITEM'
    )),
    original_object_id uuid NOT NULL,
    display_label text NOT NULL,
    deleted_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, id),
    UNIQUE (project_id, object_type, original_object_id)
);

ALTER TABLE turn_main_references
    ADD FOREIGN KEY (project_id, tombstone_id)
    REFERENCES tombstones(project_id, id) ON DELETE SET NULL (tombstone_id);

ALTER TABLE turn_quote_references
    ADD FOREIGN KEY (project_id, tombstone_id)
    REFERENCES tombstones(project_id, id) ON DELETE SET NULL (tombstone_id);

CREATE TABLE index_generations (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    embedding_model text NOT NULL,
    output_dimensionality integer NOT NULL CHECK (output_dimensionality = 1536),
    status text NOT NULL CHECK (status IN ('BUILDING', 'ACTIVE', 'SUPERSEDED', 'FAILED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz,
    UNIQUE (project_id, id)
);
CREATE UNIQUE INDEX one_active_generation_per_project
    ON index_generations (project_id) WHERE status = 'ACTIVE';

CREATE TABLE knowledge_items (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    item_type text NOT NULL CHECK (item_type IN (
        'MAIN_TURN', 'SUB_BRANCH_SELECTION', 'CONDENSED_CONTEXT_NOTE', 'PROMOTED_QUOTE'
    )),
    source_conversation_id uuid,
    source_stage_id uuid,
    source_turn_id uuid,
    source_branch_id uuid,
    promoted_from_quote_ref_id uuid,
    source_tombstone_id uuid,
    source_revision bigint NOT NULL DEFAULT 1 CHECK (source_revision > 0),
    title text,
    content text NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    soft_deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, id),
    CHECK (is_active = (soft_deleted_at IS NULL)),
    FOREIGN KEY (project_id, source_tombstone_id)
        REFERENCES tombstones(project_id, id) ON DELETE SET NULL (source_tombstone_id)
);
CREATE UNIQUE INDEX one_active_promoted_quote_per_ref
    ON knowledge_items (project_id, promoted_from_quote_ref_id)
    WHERE promoted_from_quote_ref_id IS NOT NULL AND is_active = true;
CREATE UNIQUE INDEX one_main_item_per_source_turn
    ON knowledge_items (project_id, source_turn_id)
    WHERE item_type = 'MAIN_TURN' AND source_turn_id IS NOT NULL;
CREATE INDEX knowledge_items_source_turn_idx
    ON knowledge_items (project_id, source_turn_id) WHERE source_turn_id IS NOT NULL;

CREATE TABLE knowledge_chunks (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    knowledge_item_id uuid NOT NULL,
    index_generation_id uuid NOT NULL,
    source_stage_id uuid,
    source_type text NOT NULL,
    source_revision bigint NOT NULL CHECK (source_revision > 0),
    chunk_index integer NOT NULL CHECK (chunk_index >= 0),
    content text NOT NULL,
    search_text text NOT NULL,
    search_document tsvector,
    technical_terms text[] NOT NULL DEFAULT '{}'::text[],
    embedding public.vector(1536),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, id),
    UNIQUE (project_id, index_generation_id, id),
    UNIQUE (project_id, knowledge_item_id, index_generation_id, id),
    UNIQUE (knowledge_item_id, index_generation_id, source_revision, chunk_index),
    FOREIGN KEY (project_id, knowledge_item_id)
        REFERENCES knowledge_items(project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, index_generation_id)
        REFERENCES index_generations(project_id, id) ON DELETE CASCADE
);

CREATE FUNCTION contextflow.fill_chunk_search_document()
RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    NEW.search_document := to_tsvector('public.cf_vi'::regconfig, coalesce(NEW.search_text, ''));
    RETURN NEW;
END;
$fn$;

CREATE TRIGGER trg_fill_chunk_search_document
BEFORE INSERT OR UPDATE OF search_text ON contextflow.knowledge_chunks
FOR EACH ROW EXECUTE FUNCTION contextflow.fill_chunk_search_document();

CREATE INDEX knowledge_chunks_scope_idx ON knowledge_chunks (project_id, index_generation_id, is_active);
CREATE INDEX knowledge_chunks_ancestor_stage_idx
    ON knowledge_chunks (project_id, source_stage_id, is_active) WHERE source_type = 'MAIN_TURN';
CREATE INDEX knowledge_chunks_search_gin ON knowledge_chunks USING gin (search_document);
CREATE INDEX knowledge_chunks_terms_gin ON knowledge_chunks USING gin (technical_terms);
CREATE INDEX knowledge_chunks_embedding_hnsw ON knowledge_chunks USING hnsw (embedding public.vector_cosine_ops);

CREATE TABLE embedding_outbox (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    knowledge_item_id uuid NOT NULL,
    chunk_id uuid NOT NULL,
    index_generation_id uuid NOT NULL,
    source_revision bigint NOT NULL CHECK (source_revision > 0),
    idempotency_key text NOT NULL UNIQUE,
    status text NOT NULL CHECK (status IN (
        'PENDING', 'IN_PROGRESS', 'COMPLETED', 'CONFIGURATION_BLOCKED', 'HARD_QUOTA_BLOCKED', 'FAILED', 'CANCELLED'
    )),
    error_category text CHECK (error_category IS NULL OR error_category IN (
        'TRANSIENT_RETRYABLE', 'RATE_LIMIT_RETRYABLE', 'HARD_QUOTA_BLOCKED',
        'CONFIGURATION_BLOCKED', 'PERMANENT_INPUT_ERROR', 'SOURCE_INVALIDATED', 'UNKNOWN_REQUIRES_REVIEW'
    )),
    estimated_tokens integer NOT NULL DEFAULT 0 CHECK (estimated_tokens >= 0),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_retry_at timestamptz NOT NULL DEFAULT now(),
    worker_token uuid,
    lease_expires_at timestamptz,
    last_error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (project_id, knowledge_item_id)
        REFERENCES knowledge_items(project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, knowledge_item_id, index_generation_id, chunk_id)
        REFERENCES knowledge_chunks(project_id, knowledge_item_id, index_generation_id, id) ON DELETE CASCADE,
    CHECK ((status = 'IN_PROGRESS') = (worker_token IS NOT NULL)),
    CHECK ((worker_token IS NULL) = (lease_expires_at IS NULL))
);
CREATE INDEX embedding_outbox_poll_idx ON embedding_outbox (next_retry_at, created_at) WHERE status = 'PENDING';

CREATE TABLE sot_decisions (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title text NOT NULL CHECK (length(btrim(title)) > 0),
    scope_description text NOT NULL CHECK (length(btrim(scope_description)) > 0),
    decision_content text NOT NULL,
    rationale text,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'REVOKED')),
    supersedes_decision_id uuid,
    confirmed_by_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    confirmed_at timestamptz NOT NULL DEFAULT now(),
    source_revision bigint NOT NULL DEFAULT 1 CHECK (source_revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, id),
    FOREIGN KEY (project_id, supersedes_decision_id)
        REFERENCES sot_decisions(project_id, id) ON DELETE SET NULL (supersedes_decision_id),
    CHECK (supersedes_decision_id IS DISTINCT FROM id)
);
CREATE INDEX sot_decisions_project_status_idx ON sot_decisions (project_id, status, confirmed_at DESC);
CREATE UNIQUE INDEX one_successor_per_decision
    ON sot_decisions (project_id, supersedes_decision_id) WHERE supersedes_decision_id IS NOT NULL;

CREATE TABLE sot_decision_evidences (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    decision_id uuid NOT NULL,
    evidence_kind text NOT NULL CHECK (evidence_kind IN ('KB_ITEM', 'MAIN_TURN', 'SUB_BRANCH_TURN')),
    evidence_source_id uuid NOT NULL,
    display_label text,
    evidence_status text NOT NULL DEFAULT 'AVAILABLE' CHECK (evidence_status IN ('AVAILABLE', 'UNAVAILABLE')),
    tombstone_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (project_id, decision_id)
        REFERENCES sot_decisions(project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, tombstone_id)
        REFERENCES tombstones(project_id, id) ON DELETE SET NULL (tombstone_id)
);
CREATE INDEX sot_evidences_by_source ON sot_decision_evidences (project_id, evidence_kind, evidence_source_id);

CREATE TABLE context_box_items (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    conversation_id uuid,
    target_kind text NOT NULL CHECK (target_kind IN ('MAIN_STAGE', 'CHAPTER', 'TURN', 'SUB_BRANCH')),
    target_id uuid NOT NULL,
    summary_snippet text NOT NULL DEFAULT '',
    link_status text NOT NULL DEFAULT 'AVAILABLE' CHECK (link_status IN ('AVAILABLE', 'UNAVAILABLE')),
    tombstone_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (project_id, tombstone_id)
        REFERENCES tombstones(project_id, id) ON DELETE SET NULL (tombstone_id),
    FOREIGN KEY (project_id, conversation_id)
        REFERENCES conversations(project_id, id) ON DELETE SET NULL (conversation_id)
);
CREATE INDEX context_box_by_target ON context_box_items (project_id, target_kind, target_id);
