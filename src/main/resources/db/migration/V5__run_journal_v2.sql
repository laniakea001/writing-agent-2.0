-- RG1: old rows remain protocol 1 and keep their original event sequences.
ALTER TABLE writing_v2.agent_run
    ADD COLUMN protocol_version INT NOT NULL DEFAULT 1,
    ADD COLUMN root_run_id UUID REFERENCES writing_v2.agent_run(run_id),
    ADD COLUMN parent_tool_call_id TEXT,
    ADD COLUMN terminal_sequence BIGINT,
    ADD COLUMN terminal_manifest JSONB,
    ADD COLUMN finished_at TIMESTAMPTZ,
    ADD COLUMN outcome_code TEXT;

ALTER TABLE writing_v2.agent_run ADD CONSTRAINT ck_v2_run_identity
    CHECK (protocol_version <> 2 OR root_run_id IS NOT NULL);
CREATE INDEX ix_run_root ON writing_v2.agent_run(root_run_id);

ALTER TABLE writing_v2.run_event
    ADD COLUMN producer_event_key TEXT,
    ADD COLUMN raw_event_type TEXT,
    ADD COLUMN output_type TEXT,
    ADD COLUMN schema_version INT,
    ADD COLUMN publish_required BOOLEAN,
    ADD COLUMN safe_payload JSONB,
    ADD COLUMN invocation_id UUID;
CREATE UNIQUE INDEX uq_event_producer
    ON writing_v2.run_event(run_id, producer_event_key)
    WHERE producer_event_key IS NOT NULL;
ALTER TABLE writing_v2.run_event ADD CONSTRAINT ck_v2_event_envelope
    CHECK (schema_version IS DISTINCT FROM 2 OR
           (producer_event_key IS NOT NULL AND output_type IS NOT NULL
            AND publish_required IS NOT NULL AND safe_payload IS NOT NULL));

ALTER TABLE writing_v2.conversation_message
    ADD COLUMN source_run_id UUID REFERENCES writing_v2.agent_run(run_id);
CREATE INDEX ix_message_source_run ON writing_v2.conversation_message(source_run_id)
    WHERE source_run_id IS NOT NULL;
