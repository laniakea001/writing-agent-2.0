-- H1 V4: parent/child runs, atomic event seq, tool request/result pairing, chapter source_action_id

ALTER TABLE writing_v2.agent_run
    ADD COLUMN IF NOT EXISTS parent_run_id UUID NULL
        REFERENCES writing_v2.agent_run(run_id),
    ADD COLUMN IF NOT EXISTS event_seq_counter BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS ix_agent_run_parent
    ON writing_v2.agent_run(parent_run_id);

-- Backfill counter so next append continues from existing MAX(seq)
UPDATE writing_v2.agent_run ar
SET event_seq_counter = COALESCE((
    SELECT MAX(re.seq) FROM writing_v2.run_event re WHERE re.run_id = ar.run_id
), 0);

DROP INDEX IF EXISTS writing_v2.uq_run_event_tool_call;

-- Allow TOOL_REQUEST and TOOL_RESULT to share the same tool_call_id (one row each)
CREATE UNIQUE INDEX IF NOT EXISTS uq_run_event_tool_phase
    ON writing_v2.run_event(run_id, tool_call_id, event_type)
    WHERE tool_call_id IS NOT NULL
      AND event_type IN ('TOOL_REQUEST', 'TOOL_RESULT');

CREATE INDEX IF NOT EXISTS ix_run_event_tool_call
    ON writing_v2.run_event(run_id, tool_call_id)
    WHERE tool_call_id IS NOT NULL;

ALTER TABLE writing_v2.chapter_revision
    ADD COLUMN IF NOT EXISTS source_action_id UUID NULL
        REFERENCES writing_v2.action_ledger(action_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chapter_source_action
    ON writing_v2.chapter_revision(source_action_id)
    WHERE source_action_id IS NOT NULL;

-- Optional root-action result snapshot for fixed REPLAY projection (D0)
ALTER TABLE writing_v2.action_ledger
    ADD COLUMN IF NOT EXISTS result_snapshot_json TEXT NULL,
    ADD COLUMN IF NOT EXISTS result_assistant_message_id UUID NULL;
