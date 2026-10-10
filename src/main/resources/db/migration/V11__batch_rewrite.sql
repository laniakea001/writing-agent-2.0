-- Additive only. Existing articles, single-chapter cards and Run identities remain unchanged.
CREATE TABLE writing_v2.batch_rewrite (
 batch_id UUID PRIMARY KEY,
 workspace_id UUID NOT NULL,
 project_id UUID NOT NULL,
 conversation_id UUID REFERENCES writing_v2.conversation(conversation_id),
 origin TEXT NOT NULL CHECK(origin IN ('HTTP','AGENT')),
 source_run_id UUID REFERENCES writing_v2.agent_run(run_id),
 client_action_id TEXT NOT NULL,
 request_digest TEXT NOT NULL,
 instruction TEXT NOT NULL CHECK(length(instruction)>0),
 expected_manifest JSONB NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('WAITING_REOPEN','RUNNING','WAITING_CHAPTER_CONFIRMATION','PAUSED','UNKNOWN','COMPLETED','COMPLETED_WITH_SKIPS','STOPPED')),
 version BIGINT NOT NULL DEFAULT 1,
 active_ordinal INT NOT NULL DEFAULT 1 CHECK(active_ordinal>0),
 reopen_proposal_id UUID REFERENCES writing_v2.confirmation_proposal(proposal_id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(workspace_id,project_id) REFERENCES writing_v2.writing_project(workspace_id,project_id),
 UNIQUE(project_id,client_action_id)
);
CREATE UNIQUE INDEX uq_active_rewrite_batch ON writing_v2.batch_rewrite(project_id)
 WHERE status NOT IN ('COMPLETED','COMPLETED_WITH_SKIPS','STOPPED');
CREATE TABLE writing_v2.batch_rewrite_item (
 item_id UUID PRIMARY KEY,
 batch_id UUID NOT NULL REFERENCES writing_v2.batch_rewrite(batch_id),
 ordinal INT NOT NULL CHECK(ordinal>0),
 chapter_key UUID NOT NULL REFERENCES writing_v2.article_chapter(chapter_key),
 chapter_index INT NOT NULL CHECK(chapter_index>0),
 initial_revision_id UUID REFERENCES writing_v2.chapter_revision(chapter_revision_id),
 expected_revision_id UUID REFERENCES writing_v2.chapter_revision(chapter_revision_id),
 status TEXT NOT NULL CHECK(status IN ('PENDING','GENERATING','WAITING_CONFIRMATION','APPLIED','REJECTED','FAILED','UNKNOWN')),
 generation_run_id UUID REFERENCES writing_v2.agent_run(run_id),
 generation_claimed_at TIMESTAMPTZ,
 generation_owner UUID,
 proposal_id UUID UNIQUE REFERENCES writing_v2.confirmation_proposal(proposal_id),
 decision_action_id UUID REFERENCES writing_v2.action_ledger(action_id),
 receipt_json JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(batch_id,ordinal),UNIQUE(batch_id,chapter_key)
);
