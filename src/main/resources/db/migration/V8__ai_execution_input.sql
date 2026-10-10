-- Immutable request snapshot; GET never resumes generation.
ALTER TABLE writing_v2.action_ledger ADD COLUMN execution_request_json JSONB;
