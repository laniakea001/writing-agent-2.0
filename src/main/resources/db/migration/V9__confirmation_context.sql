ALTER TABLE writing_v2.confirmation_proposal
    ADD COLUMN conversation_id uuid REFERENCES writing_v2.conversation(conversation_id),
    ADD COLUMN source_run_id uuid REFERENCES writing_v2.agent_run(run_id);
CREATE INDEX confirmation_conversation_page_idx
    ON writing_v2.confirmation_proposal(conversation_id, created_at DESC, proposal_id);

-- Historical cards are linked only where the journal proves a single conversation.
-- Ambiguous or absent links remain unbound; never guess from the latest run.
WITH links AS (
    SELECT (e.safe_payload->>'proposalId')::uuid AS proposal_id,
           t.conversation_id, r.run_id
    FROM writing_v2.run_event e
    JOIN writing_v2.agent_run r ON r.run_id=e.run_id
    JOIN writing_v2.writing_task t ON t.task_id=r.task_id
    JOIN writing_v2.confirmation_proposal p
      ON p.proposal_id::text=e.safe_payload->>'proposalId'
     AND p.workspace_id=t.workspace_id
    WHERE e.output_type='CONFIRMATION_REQUIRED' AND t.conversation_id IS NOT NULL
), unique_links AS (
    SELECT proposal_id, min(conversation_id::text)::uuid AS conversation_id,
           min(run_id::text)::uuid AS run_id
    FROM links GROUP BY proposal_id HAVING count(DISTINCT conversation_id)=1
)
UPDATE writing_v2.confirmation_proposal p
SET conversation_id=u.conversation_id, source_run_id=u.run_id
FROM unique_links u WHERE p.proposal_id=u.proposal_id;
