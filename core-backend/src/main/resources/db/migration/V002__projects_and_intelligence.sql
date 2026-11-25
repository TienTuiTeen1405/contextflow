-- Source: BranOS-03-5, page 7536718, version 2 (2026-11-25), section 4.
-- https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536718
-- ContextFlow V6 — V002: Projects & Project Intelligence (Layer 2)
SET LOCAL search_path TO contextflow, public;

CREATE TABLE projects (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    description text,
    project_information text,
    project_intelligence_enabled boolean NOT NULL DEFAULT true,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETING', 'DELETED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX projects_owner_status_idx ON projects (owner_user_id, status, updated_at DESC);

CREATE TABLE project_rules (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    content text NOT NULL,
    enforcement_level text NOT NULL DEFAULT 'OVERRIDABLE'
        CHECK (enforcement_level IN ('MANDATORY', 'OVERRIDABLE')),
    priority integer NOT NULL DEFAULT 100 CHECK (priority >= 0),
    is_enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX project_rules_active_order_idx
    ON project_rules (project_id, enforcement_level, priority, id)
    WHERE is_enabled = true;

CREATE TABLE project_skills (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    description text,
    content text NOT NULL,
    priority integer NOT NULL DEFAULT 100 CHECK (priority >= 0),
    is_enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX project_skills_active_order_idx
    ON project_skills (project_id, priority, id)
    WHERE is_enabled = true;
