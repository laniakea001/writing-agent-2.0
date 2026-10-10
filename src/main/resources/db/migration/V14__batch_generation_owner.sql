ALTER TABLE writing_v2.batch_rewrite_item ADD COLUMN generation_owner_pid bigint;
-- Never invent an owner for historical diagnostics.
