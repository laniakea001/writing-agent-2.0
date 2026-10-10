-- D2: workspace-owned conversations and action scope, preserving historical project keys.
-- Execute against a snapshot first. Never infer missing scope from the latest project.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM writing_v2.agent_run GROUP BY action_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'D2_PRECHECK_DUPLICATE_ACTION_RUN';
    END IF;
    IF EXISTS (SELECT 1 FROM writing_v2.action_ledger a
               JOIN writing_v2.conversation c USING (conversation_id)
               JOIN writing_v2.writing_project ap ON ap.project_id=a.project_id
               JOIN writing_v2.writing_project cp ON cp.project_id=c.project_id
               WHERE ap.workspace_id<>cp.workspace_id) OR
       EXISTS (SELECT 1 FROM writing_v2.writing_task t
               JOIN writing_v2.conversation c USING (conversation_id)
               JOIN writing_v2.writing_project tp ON tp.project_id=t.project_id
               JOIN writing_v2.writing_project cp ON cp.project_id=c.project_id
               WHERE tp.workspace_id<>cp.workspace_id) THEN
        RAISE EXCEPTION 'D2_PRECHECK_CROSS_WORKSPACE';
    END IF;
END $$;

ALTER TABLE writing_v2.writing_project
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}',
    ADD CONSTRAINT uq_project_workspace UNIQUE(workspace_id, project_id);

ALTER TABLE writing_v2.conversation
    ADD COLUMN workspace_id UUID,
    ADD COLUMN agent_id TEXT NOT NULL DEFAULT 'writing-main',
    ADD COLUMN display_name TEXT,
    ADD COLUMN active_context_version BIGINT NOT NULL DEFAULT 1,
    ALTER COLUMN project_id DROP NOT NULL;
UPDATE writing_v2.conversation c SET workspace_id=p.workspace_id
    FROM writing_v2.writing_project p WHERE p.project_id=c.project_id;
ALTER TABLE writing_v2.conversation
    ALTER COLUMN workspace_id SET NOT NULL,
    ADD CONSTRAINT fk_conversation_workspace FOREIGN KEY(workspace_id) REFERENCES writing_v2.workspace(workspace_id),
    ADD CONSTRAINT uq_conversation_workspace UNIQUE(workspace_id, conversation_id),
    ADD CONSTRAINT fk_conversation_active_project FOREIGN KEY(workspace_id, project_id)
        REFERENCES writing_v2.writing_project(workspace_id, project_id),
    ADD CONSTRAINT ck_conversation_context_version CHECK(active_context_version>0);

ALTER TABLE writing_v2.action_ledger
    ADD COLUMN workspace_id UUID,
    ADD COLUMN scope_key TEXT,
    ADD COLUMN digest_version INT NOT NULL DEFAULT 1,
    ADD COLUMN target_chapter_key UUID,
    ADD COLUMN followup_token TEXT,
    ADD COLUMN receipt_json JSONB,
    ALTER COLUMN project_id DROP NOT NULL;
UPDATE writing_v2.action_ledger a SET workspace_id=p.workspace_id, scope_key='project:'||p.project_id
    FROM writing_v2.writing_project p WHERE p.project_id=a.project_id;
ALTER TABLE writing_v2.action_ledger
    ALTER COLUMN workspace_id SET NOT NULL,
    ALTER COLUMN scope_key SET NOT NULL,
    ADD CONSTRAINT uq_action_workspace_scope UNIQUE(workspace_id, scope_key, client_action_id),
    ADD CONSTRAINT fk_action_workspace FOREIGN KEY(workspace_id) REFERENCES writing_v2.workspace(workspace_id),
    ADD CONSTRAINT fk_action_scope_project FOREIGN KEY(workspace_id, project_id)
        REFERENCES writing_v2.writing_project(workspace_id, project_id),
    ADD CONSTRAINT fk_action_scope_conversation FOREIGN KEY(workspace_id, conversation_id)
        REFERENCES writing_v2.conversation(workspace_id, conversation_id),
    ADD CONSTRAINT ck_action_scope_nonempty CHECK(length(trim(scope_key))>0),
    ADD CONSTRAINT ck_action_digest_version CHECK(digest_version>0);
CREATE UNIQUE INDEX uq_action_followup ON writing_v2.action_ledger(workspace_id, followup_token)
    WHERE followup_token IS NOT NULL;

ALTER TABLE writing_v2.writing_task ADD COLUMN workspace_id UUID, ALTER COLUMN project_id DROP NOT NULL;
UPDATE writing_v2.writing_task t SET workspace_id=p.workspace_id
    FROM writing_v2.writing_project p WHERE p.project_id=t.project_id;
ALTER TABLE writing_v2.writing_task
    ALTER COLUMN workspace_id SET NOT NULL,
    ADD CONSTRAINT fk_task_workspace FOREIGN KEY(workspace_id) REFERENCES writing_v2.workspace(workspace_id),
    ADD CONSTRAINT fk_task_scope_project FOREIGN KEY(workspace_id, project_id)
        REFERENCES writing_v2.writing_project(workspace_id, project_id),
    ADD CONSTRAINT fk_task_scope_conversation FOREIGN KEY(workspace_id, conversation_id)
        REFERENCES writing_v2.conversation(workspace_id, conversation_id);

ALTER TABLE writing_v2.agent_run
    ADD COLUMN execution_owner_id UUID,
    ADD COLUMN execution_owner_pid INT,
    ADD COLUMN execution_started_at TIMESTAMPTZ,
    ADD CONSTRAINT uq_run_action UNIQUE(action_id);

CREATE TABLE writing_v2.conversation_run_admission (
    conversation_id UUID PRIMARY KEY REFERENCES writing_v2.conversation(conversation_id),
    active_root_run_id UUID REFERENCES writing_v2.agent_run(run_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO writing_v2.conversation_run_admission(conversation_id)
    SELECT conversation_id FROM writing_v2.conversation;
CREATE INDEX ix_task_workspace ON writing_v2.writing_task(workspace_id, updated_at, task_id);
