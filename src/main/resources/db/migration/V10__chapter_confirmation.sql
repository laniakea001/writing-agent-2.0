ALTER TABLE writing_v2.confirmation_proposal DROP CONSTRAINT confirmation_proposal_proposal_type_check;
ALTER TABLE writing_v2.confirmation_proposal ADD CONSTRAINT confirmation_proposal_proposal_type_check
    CHECK(proposal_type IN ('CONFIRM_PLAN','COMPLETE_ARTICLE','REOPEN_ARTICLE','CONFIRM_CHAPTER'));
