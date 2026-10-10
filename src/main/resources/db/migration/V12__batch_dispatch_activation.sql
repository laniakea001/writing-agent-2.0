-- Explicit opt-in. Existing diagnostic/test batches must never invoke models on startup.
ALTER TABLE writing_v2.batch_rewrite ADD COLUMN dispatch_activated_at timestamptz;
