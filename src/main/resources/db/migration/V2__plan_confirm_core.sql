-- CP1: plan-confirm core schema (writing_v2). Evolves beyond CP0 probe.

CREATE TABLE writing_v2.workspace (
    workspace_id        UUID PRIMARY KEY,
    display_name        TEXT NOT NULL,
    owner_scope         TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE writing_v2.writing_project (
    project_id                  UUID PRIMARY KEY,
    workspace_id                UUID NOT NULL REFERENCES writing_v2.workspace(workspace_id),
    title                       TEXT NOT NULL,
    project_version             BIGINT NOT NULL DEFAULT 1,
    current_plan_revision_id    UUID NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE writing_v2.specification_revision (
    spec_revision_id    UUID PRIMARY KEY,
    project_id          UUID NOT NULL REFERENCES writing_v2.writing_project(project_id),
    revision_no         BIGINT NOT NULL,
    research_goal       TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, revision_no)
);

CREATE TABLE writing_v2.conversation (
    conversation_id     UUID PRIMARY KEY,
    project_id          UUID NOT NULL REFERENCES writing_v2.writing_project(project_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE writing_v2.conversation_message (
    message_id          UUID PRIMARY KEY,
    conversation_id     UUID NOT NULL REFERENCES writing_v2.conversation(conversation_id),
    role                TEXT NOT NULL,
    body                TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conv_msg_conversation ON writing_v2.conversation_message(conversation_id, created_at);

-- Action ledger: project-scoped idempotency
CREATE TABLE writing_v2.action_ledger (
    action_id           UUID PRIMARY KEY,
    project_id          UUID NOT NULL REFERENCES writing_v2.writing_project(project_id),
    client_action_id    TEXT NOT NULL,
    request_digest      TEXT NOT NULL,
    operation           TEXT NOT NULL,
    status              TEXT NOT NULL,
    conversation_id     UUID NULL,
    task_id             UUID NULL,
    run_id              UUID NULL,
    plan_revision_id    UUID NULL,
    result_may_exist    BOOLEAN NOT NULL DEFAULT FALSE,
    error_code          TEXT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, client_action_id)
);

CREATE TABLE writing_v2.writing_task (
    task_id             UUID PRIMARY KEY,
    project_id          UUID NOT NULL REFERENCES writing_v2.writing_project(project_id),
    conversation_id     UUID NOT NULL REFERENCES writing_v2.conversation(conversation_id),
    status              TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE writing_v2.agent_run (
    run_id              UUID PRIMARY KEY,
    task_id             UUID NOT NULL REFERENCES writing_v2.writing_task(task_id),
    action_id           UUID NOT NULL REFERENCES writing_v2.action_ledger(action_id),
    status              TEXT NOT NULL,
    model_request_count INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE writing_v2.run_event (
    event_id            UUID PRIMARY KEY,
    run_id              UUID NOT NULL REFERENCES writing_v2.agent_run(run_id),
    seq                 BIGINT NOT NULL,
    event_type          TEXT NOT NULL,
    tool_call_id        TEXT NULL,
    correlation_id      TEXT NULL,
    summary             TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (run_id, seq)
);

-- Dedup observer double-subscribe by tool_call_id within a run when present
CREATE UNIQUE INDEX uq_run_event_tool_call
    ON writing_v2.run_event(run_id, tool_call_id)
    WHERE tool_call_id IS NOT NULL;

CREATE TABLE writing_v2.plan_revision (
    plan_revision_id    UUID PRIMARY KEY,
    project_id          UUID NOT NULL REFERENCES writing_v2.writing_project(project_id),
    revision_no         BIGINT NOT NULL,
    status              TEXT NOT NULL,
    payload_json        JSONB NOT NULL,
    content_hash        TEXT NOT NULL,
    source_basis        TEXT NOT NULL,
    confirmed_at        TIMESTAMPTZ NULL,
    confirmed_by        TEXT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, revision_no)
);

ALTER TABLE writing_v2.writing_project
    ADD CONSTRAINT fk_project_current_plan
    FOREIGN KEY (current_plan_revision_id)
    REFERENCES writing_v2.plan_revision(plan_revision_id);

CREATE INDEX idx_action_project ON writing_v2.action_ledger(project_id);
CREATE INDEX idx_task_project ON writing_v2.writing_task(project_id);
CREATE INDEX idx_plan_project ON writing_v2.plan_revision(project_id, revision_no DESC);
