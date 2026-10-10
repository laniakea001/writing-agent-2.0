CREATE TABLE writing_v2.batch_control_receipt (
 batch_id uuid NOT NULL REFERENCES writing_v2.batch_rewrite(batch_id),
 client_action_id varchar(300) NOT NULL,
 request_digest varchar(64) NOT NULL,
 receipt_json jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(batch_id,client_action_id)
);
