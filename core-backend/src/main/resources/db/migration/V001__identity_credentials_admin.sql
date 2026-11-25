-- Source: BranOS-03-5, page 7536718, version 2 (2026-11-25), section 4.
-- https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536718
-- ContextFlow V6 — V001: Identity, Auth Sessions, Credentials, Admin & ADM-07 Counters
CREATE SCHEMA IF NOT EXISTS contextflow;
SET LOCAL search_path TO contextflow, public;

CREATE TABLE users (
    id uuid PRIMARY KEY,
    email text NOT NULL UNIQUE CHECK (length(btrim(email)) > 3),
    password_hash text,
    display_name text NOT NULL CHECK (length(btrim(display_name)) > 0),
    role text NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    theme_preference text NOT NULL DEFAULT 'SYSTEM' CHECK (theme_preference IN ('LIGHT', 'DARK', 'SYSTEM')),
    personal_intelligence_enabled boolean NOT NULL DEFAULT true,
    personal_profile_text text,
    personal_instructions text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_oauth_accounts (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider text NOT NULL CHECK (provider IN ('GOOGLE')),
    provider_subject text NOT NULL,
    email text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_subject)
);

CREATE TABLE auth_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash bytea NOT NULL UNIQUE CHECK (octet_length(refresh_token_hash) = 32),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at)
);
CREATE INDEX auth_sessions_user ON auth_sessions (user_id, expires_at);

CREATE TABLE personal_skills (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    description text,
    content text NOT NULL,
    priority integer NOT NULL DEFAULT 100 CHECK (priority >= 0),
    is_enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX personal_skills_user_idx ON personal_skills (user_id, is_enabled, priority, id);

CREATE TABLE ai_credentials (
    id uuid PRIMARY KEY,
    owner_scope text NOT NULL CHECK (owner_scope IN ('PLATFORM', 'USER')),
    owner_user_id uuid REFERENCES users(id) ON DELETE CASCADE,
    credential_version bigint NOT NULL CHECK (credential_version > 0),
    status text NOT NULL CHECK (status IN ('ACTIVE', 'ROTATED', 'REMOVED', 'REVOKED', 'BLOCKED')),
    masked_label text NOT NULL,
    encrypted_secret bytea NOT NULL,
    wrapped_dek bytea NOT NULL,
    kek_version text NOT NULL,
    declared_tier text NOT NULL CHECK (declared_tier IN ('FREE', 'PAID', 'UNKNOWN')),
    google_cloud_project_ref text,
    verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((owner_scope = 'USER') = (owner_user_id IS NOT NULL))
);

CREATE UNIQUE INDEX one_active_platform_credential
    ON ai_credentials (owner_scope)
    WHERE owner_scope = 'PLATFORM' AND status = 'ACTIVE';

CREATE UNIQUE INDEX one_active_user_credential
    ON ai_credentials (owner_user_id)
    WHERE owner_scope = 'USER' AND status = 'ACTIVE';

CREATE TABLE privacy_disclosure_acknowledgements (
    id uuid PRIMARY KEY,
    actor_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_id uuid NOT NULL REFERENCES ai_credentials(id) ON DELETE CASCADE,
    credential_version bigint NOT NULL CHECK (credential_version > 0),
    disclosure_version text NOT NULL,
    scope text NOT NULL CHECK (scope IN ('CREDENTIAL_SETUP', 'WORKSPACE_OPERATION')),
    effective_tier text NOT NULL CHECK (effective_tier IN ('FREE', 'UNKNOWN')),
    google_cloud_project_ref text,
    acknowledged_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (actor_user_id, credential_id, credential_version, disclosure_version, scope)
);

CREATE TABLE task_model_registry (
    task_type text PRIMARY KEY CHECK (task_type IN (
        'CHAT_MAIN', 'CHAT_SUB_BRANCH',
        'MAIN_STAGE_CONDENSATION', 'CONVERSATION_CONDENSATION',
        'SUB_BRANCH_CHERRY_PICK_CONDENSE', 'SUB_BRANCH_CONDENSE_ALL',
        'CHAPTER_TOPIC_DETECTION', 'CHAPTER_SUMMARY_GENERATION',
        'KB_CHUNK_EMBEDDING', 'QUERY_EMBEDDING'
    )),
    credential_owner_scope text NOT NULL CHECK (credential_owner_scope IN ('USER', 'PLATFORM')),
    api_interface text NOT NULL CHECK (api_interface IN ('INTERACTIONS', 'EMBEDDINGS', 'GENERATE_CONTENT')),
    primary_model text NOT NULL,
    fallback_model text,
    is_enabled boolean NOT NULL DEFAULT true,
    updated_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Baseline CF-DEC-037/047; DES-DIFF-01: keep gemini-3.8-flash for all text tasks.
INSERT INTO task_model_registry (task_type, credential_owner_scope, api_interface, primary_model, fallback_model) VALUES
    ('CHAT_MAIN', 'USER', 'INTERACTIONS', 'gemini-3.8-flash', NULL),
    ('CHAT_SUB_BRANCH', 'USER', 'INTERACTIONS', 'gemini-3.8-flash', NULL),
    ('MAIN_STAGE_CONDENSATION', 'PLATFORM', 'INTERACTIONS', 'gemini-3.8-flash', NULL),
    ('CONVERSATION_CONDENSATION', 'PLATFORM', 'INTERACTIONS', 'gemini-3.8-flash', NULL),
    ('SUB_BRANCH_CHERRY_PICK_CONDENSE', 'PLATFORM', 'INTERACTIONS', 'gemini-3.8-flash', NULL),
    ('SUB_BRANCH_CONDENSE_ALL', 'PLATFORM', 'INTERACTIONS', 'gemini-3.8-flash', NULL),
    ('CHAPTER_TOPIC_DETECTION', 'PLATFORM', 'INTERACTIONS', 'gemini-3.8-flash', NULL),
    ('CHAPTER_SUMMARY_GENERATION', 'PLATFORM', 'INTERACTIONS', 'gemini-3.8-flash', NULL),
    ('KB_CHUNK_EMBEDDING', 'PLATFORM', 'EMBEDDINGS', 'gemini-embedding-2', NULL),
    ('QUERY_EMBEDDING', 'PLATFORM', 'EMBEDDINGS', 'gemini-embedding-2', NULL);

CREATE TABLE platform_settings (
    setting_key text PRIMARY KEY,
    setting_value jsonb NOT NULL,
    description text,
    updated_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE intelligence_templates (
    id uuid PRIMARY KEY,
    template_type text NOT NULL CHECK (template_type IN ('PERSONA', 'PROJECT_RULE', 'PROJECT_SKILL')),
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    description text,
    content text NOT NULL,
    default_enforcement_level text CHECK (
        default_enforcement_level IS NULL OR default_enforcement_level IN ('MANDATORY', 'OVERRIDABLE')
    ),
    created_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((template_type = 'PROJECT_RULE') = (default_enforcement_level IS NOT NULL))
);

CREATE TABLE prompt_templates (
    id uuid PRIMARY KEY,
    task_type text NOT NULL REFERENCES task_model_registry(task_type),
    version integer NOT NULL CHECK (version > 0),
    name text NOT NULL,
    template_body text NOT NULL,
    is_active boolean NOT NULL DEFAULT false,
    updated_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (task_type, version)
);

CREATE UNIQUE INDEX one_active_prompt_template_per_task
    ON prompt_templates (task_type)
    WHERE is_active = true;

CREATE TABLE usage_quota_limits (
    id uuid PRIMARY KEY,
    scope_type text NOT NULL CHECK (scope_type IN ('GLOBAL_DEFAULT', 'USER', 'PROJECT')),
    scope_id uuid,
    max_monthly_tokens bigint CHECK (max_monthly_tokens IS NULL OR max_monthly_tokens >= 0),
    max_project_rules integer CHECK (max_project_rules IS NULL OR max_project_rules >= 0),
    max_project_skills integer CHECK (max_project_skills IS NULL OR max_project_skills >= 0),
    max_storage_bytes bigint CHECK (max_storage_bytes IS NULL OR max_storage_bytes >= 0),
    updated_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((scope_type = 'GLOBAL_DEFAULT') = (scope_id IS NULL))
);

CREATE UNIQUE INDEX unique_quota_limit_scope
    ON usage_quota_limits (scope_type, scope_id) NULLS NOT DISTINCT;

CREATE TABLE user_usage_counters (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    period_key text NOT NULL,
    total_input_tokens bigint NOT NULL DEFAULT 0 CHECK (total_input_tokens >= 0),
    total_output_tokens bigint NOT NULL DEFAULT 0 CHECK (total_output_tokens >= 0),
    total_requests bigint NOT NULL DEFAULT 0 CHECK (total_requests >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, period_key)
);
