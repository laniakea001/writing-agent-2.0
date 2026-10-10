-- CP0 probe migration only (not full plan-confirm schema; that is CP1+).
-- Schema: writing_v2 on database writing_agent_2 (isolated from academic_core).

CREATE TABLE IF NOT EXISTS writing_v2.cp0_probe (
    id              BIGSERIAL PRIMARY KEY,
    probe_key       TEXT NOT NULL UNIQUE,
    note            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO writing_v2.cp0_probe (probe_key, note)
VALUES ('cp0-flyway-replay', 'CP0 empty-schema Flyway replay marker')
ON CONFLICT (probe_key) DO NOTHING;
