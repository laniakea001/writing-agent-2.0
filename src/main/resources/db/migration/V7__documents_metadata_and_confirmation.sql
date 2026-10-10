-- D2: JSON is authority for NEW revisions; legacy Markdown/hash remain untouched.
ALTER TABLE writing_v2.article
    ADD COLUMN article_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN completion_manifest JSONB;

CREATE TABLE writing_v2.legacy_outline_key (
    project_id UUID NOT NULL REFERENCES writing_v2.writing_project(project_id),
    chapter_index INT NOT NULL CHECK(chapter_index>0),
    chapter_key UUID NOT NULL UNIQUE,
    PRIMARY KEY(project_id, chapter_index)
);
-- Historical plans are not rewritten. Allocate one stable key for each known slot.
WITH counts AS (
    SELECT p.project_id,
        GREATEST(COALESCE((SELECT max(a.chapter_count) FROM writing_v2.article a WHERE a.project_id=p.project_id),0),
                 COALESCE((SELECT max(cr.chapter_index) FROM writing_v2.chapter_revision cr
                    JOIN writing_v2.article a USING(article_id) WHERE a.project_id=p.project_id),0),
                 COALESCE((SELECT max(CASE
                    WHEN jsonb_typeof(pr.payload_json#>'{plan,chapterOutline}')='array'
                        THEN jsonb_array_length(pr.payload_json#>'{plan,chapterOutline}')
                    WHEN jsonb_typeof(pr.payload_json->'chapterOutline')='array'
                        THEN jsonb_array_length(pr.payload_json->'chapterOutline') ELSE 0 END)
                    FROM writing_v2.plan_revision pr WHERE pr.project_id=p.project_id),0)) AS n
    FROM writing_v2.writing_project p
)
INSERT INTO writing_v2.legacy_outline_key(project_id,chapter_index,chapter_key)
    SELECT c.project_id,i,gen_random_uuid() FROM counts c CROSS JOIN LATERAL generate_series(1,c.n) i;

CREATE TABLE writing_v2.article_chapter (
    chapter_key UUID PRIMARY KEY,
    article_id UUID NOT NULL REFERENCES writing_v2.article(article_id),
    chapter_index INT NOT NULL CHECK(chapter_index>0),
    title TEXT NOT NULL,
    brief TEXT NOT NULL DEFAULT '',
    UNIQUE(article_id,chapter_index),
    UNIQUE(article_id,chapter_key)
);
INSERT INTO writing_v2.article_chapter(chapter_key,article_id,chapter_index,title)
    SELECT k.chapter_key,a.article_id,k.chapter_index,
        COALESCE(pr.payload_json#>>ARRAY['plan','chapterOutline',(k.chapter_index-1)::text],
                 pr.payload_json#>>ARRAY['chapterOutline',(k.chapter_index-1)::text],
                 (SELECT cr.title FROM writing_v2.chapter_revision cr WHERE cr.article_id=a.article_id
                    AND cr.chapter_index=k.chapter_index ORDER BY cr.revision_no DESC LIMIT 1),
                 '第'||k.chapter_index||'章')
    FROM writing_v2.article a JOIN writing_v2.legacy_outline_key k ON k.project_id=a.project_id
    JOIN writing_v2.plan_revision pr ON pr.plan_revision_id=a.plan_revision_id
    WHERE k.chapter_index<=GREATEST(a.chapter_count,COALESCE((SELECT max(cr.chapter_index)
        FROM writing_v2.chapter_revision cr WHERE cr.article_id=a.article_id),0));

ALTER TABLE writing_v2.chapter_revision
    ADD COLUMN chapter_key UUID,
    ADD COLUMN content_json JSONB,
    ADD COLUMN document_hash TEXT,
    ADD COLUMN codec_version INT;
UPDATE writing_v2.chapter_revision r SET chapter_key=c.chapter_key FROM writing_v2.article_chapter c
    WHERE c.article_id=r.article_id AND c.chapter_index=r.chapter_index;
ALTER TABLE writing_v2.chapter_revision
    ALTER COLUMN chapter_key SET NOT NULL,
    ADD CONSTRAINT fk_revision_chapter FOREIGN KEY(article_id,chapter_key)
        REFERENCES writing_v2.article_chapter(article_id,chapter_key),
    ADD CONSTRAINT ck_revision_document_bundle CHECK(
        (content_json IS NULL AND document_hash IS NULL AND codec_version IS NULL) OR
        (content_json IS NOT NULL AND document_hash IS NOT NULL AND codec_version IS NOT NULL AND codec_version=1));
ALTER TABLE writing_v2.action_ledger ADD CONSTRAINT fk_action_target_chapter
    FOREIGN KEY(target_chapter_key) REFERENCES writing_v2.article_chapter(chapter_key);

CREATE TABLE writing_v2.article_metadata_revision (
    metadata_revision_id UUID PRIMARY KEY,
    article_id UUID NOT NULL REFERENCES writing_v2.article(article_id),
    revision_no BIGINT NOT NULL CHECK(revision_no>0),
    status TEXT NOT NULL CHECK(status IN ('CURRENT','SUPERSEDED')),
    payload_json JSONB NOT NULL,
    source_basis TEXT NOT NULL,
    source_action_id UUID REFERENCES writing_v2.action_ledger(action_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(article_id,revision_no),
    UNIQUE(article_id,metadata_revision_id)
);
CREATE UNIQUE INDEX uq_metadata_current ON writing_v2.article_metadata_revision(article_id) WHERE status='CURRENT';
ALTER TABLE writing_v2.article ADD COLUMN current_metadata_revision_id UUID,
    ADD CONSTRAINT fk_article_current_metadata FOREIGN KEY(article_id,current_metadata_revision_id)
        REFERENCES writing_v2.article_metadata_revision(article_id,metadata_revision_id);

CREATE TABLE writing_v2.confirmation_proposal (
    proposal_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES writing_v2.workspace(workspace_id),
    project_id UUID NOT NULL,
    requested_by TEXT NOT NULL,
    origin TEXT NOT NULL CHECK(origin IN ('HTTP','AGENT')),
    proposal_type TEXT NOT NULL CHECK(proposal_type IN ('CONFIRM_PLAN','COMPLETE_ARTICLE','REOPEN_ARTICLE')),
    target_revision_id UUID,
    expected_project_version BIGINT NOT NULL,
    expected_article_version BIGINT,
    target_snapshot JSONB NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('OPEN','APPROVED','REJECTED','EXPIRED','STALE')),
    expires_at TIMESTAMPTZ NOT NULL,
    decision_action_id UUID REFERENCES writing_v2.action_ledger(action_id),
    receipt_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(workspace_id,project_id) REFERENCES writing_v2.writing_project(workspace_id,project_id)
);
CREATE INDEX ix_proposal_project_status ON writing_v2.confirmation_proposal(project_id,status,created_at);

CREATE TABLE writing_v2.generation_suggestion (
    suggestion_id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES writing_v2.writing_project(project_id),
    kind TEXT NOT NULL CHECK(kind IN ('POLISH_SELECTION','ARTICLE_METADATA')),
    source_plan_revision_id UUID NOT NULL REFERENCES writing_v2.plan_revision(plan_revision_id),
    source_article_version BIGINT NOT NULL,
    source_chapter_revision_id UUID REFERENCES writing_v2.chapter_revision(chapter_revision_id),
    selection_from INT,
    selection_to INT,
    selection_hash TEXT,
    payload_json JSONB NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('OPEN','APPLIED','STALE')),
    source_action_id UUID REFERENCES writing_v2.action_ledger(action_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK((kind='ARTICLE_METADATA' AND selection_from IS NULL AND selection_to IS NULL AND selection_hash IS NULL)
       OR (kind='POLISH_SELECTION' AND source_chapter_revision_id IS NOT NULL
           AND selection_from IS NOT NULL AND selection_to IS NOT NULL AND selection_hash IS NOT NULL
           AND selection_from>=0 AND selection_to>selection_from))
);
