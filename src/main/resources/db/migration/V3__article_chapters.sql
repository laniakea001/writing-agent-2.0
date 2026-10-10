-- S2: article + chapter revisions (writing_v2). Confirmed plan drives chapter_count.

CREATE TABLE writing_v2.article (
    article_id              UUID PRIMARY KEY,
    project_id              UUID NOT NULL UNIQUE
        REFERENCES writing_v2.writing_project(project_id),
    plan_revision_id        UUID NOT NULL
        REFERENCES writing_v2.plan_revision(plan_revision_id),
    status                  TEXT NOT NULL,
    title                   TEXT NOT NULL,
    chapter_count           INT NOT NULL,
    completed_chapter_count INT NOT NULL DEFAULT 0,
    external_sources_used   BOOLEAN NOT NULL DEFAULT FALSE,
    evidence_basis          TEXT NOT NULL,
    completed_at            TIMESTAMPTZ NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE writing_v2.chapter_revision (
    chapter_revision_id UUID PRIMARY KEY,
    article_id          UUID NOT NULL
        REFERENCES writing_v2.article(article_id),
    chapter_index       INT NOT NULL,
    revision_no         BIGINT NOT NULL,
    status              TEXT NOT NULL,
    title               TEXT NOT NULL,
    body_markdown       TEXT NOT NULL,
    content_hash        TEXT NOT NULL,
    source_basis        TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (article_id, chapter_index, revision_no),
    CHECK (chapter_index >= 1)
);

CREATE UNIQUE INDEX uq_chapter_current
    ON writing_v2.chapter_revision(article_id, chapter_index)
    WHERE status = 'CURRENT';

CREATE INDEX idx_chapter_article ON writing_v2.chapter_revision(article_id, chapter_index);

ALTER TABLE writing_v2.writing_project
    ADD COLUMN current_article_id UUID NULL;

ALTER TABLE writing_v2.writing_project
    ADD CONSTRAINT fk_project_current_article
    FOREIGN KEY (current_article_id)
    REFERENCES writing_v2.article(article_id);
