package com.writingagent.v2.infrastructure.jdbc;

import com.writingagent.v2.application.port.ArticlePort;
import com.writingagent.v2.domain.ChapterRevisionStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcArticleAdapter implements ArticlePort {

    private final JdbcTemplate jdbc;

    public JdbcArticleAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertArticle(
            UUID articleId,
            UUID projectId,
            UUID planRevisionId,
            String status,
            String title,
            int chapterCount,
            boolean externalSourcesUsed,
            String evidenceBasis) {
        jdbc.update(
                """
                INSERT INTO writing_v2.article(
                  article_id, project_id, plan_revision_id, status, title, chapter_count,
                  completed_chapter_count, external_sources_used, evidence_basis)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                articleId,
                projectId,
                planRevisionId,
                status,
                title,
                chapterCount,
                externalSourcesUsed,
                evidenceBasis);
    }

    @Override
    public Optional<ArticleView> findByProjectId(UUID projectId) {
        try {
            return Optional.of(
                    mapArticle(
                            jdbc.queryForMap(
                                    """
                                    SELECT * FROM writing_v2.article WHERE project_id = ?
                                    """,
                                    projectId)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ArticleView> findById(UUID articleId) {
        try {
            return Optional.of(
                    mapArticle(
                            jdbc.queryForMap(
                                    "SELECT * FROM writing_v2.article WHERE article_id = ?",
                                    articleId)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void updateArticleStatus(
            UUID articleId, String status, int completedChapterCount, Instant completedAt) {
        jdbc.update(
                """
                UPDATE writing_v2.article
                SET status = ?, completed_chapter_count = ?, completed_at = ?, updated_at = NOW()
                WHERE article_id = ?
                """,
                status,
                completedChapterCount,
                completedAt == null ? null : Timestamp.from(completedAt),
                articleId);
    }

    @Override
    public void insertChapterCurrent(
            UUID chapterRevisionId,
            UUID articleId,
            int chapterIndex,
            long revisionNo,
            String title,
            String bodyMarkdown,
            String contentHash,
            String sourceBasis) {
        insertChapterCurrent(
                chapterRevisionId,
                articleId,
                chapterIndex,
                revisionNo,
                title,
                bodyMarkdown,
                contentHash,
                sourceBasis,
                null);
    }

    @Override
    public void insertChapterCurrent(
            UUID chapterRevisionId,
            UUID articleId,
            int chapterIndex,
            long revisionNo,
            String title,
            String bodyMarkdown,
            String contentHash,
            String sourceBasis,
            UUID sourceActionId) {
        jdbc.update(
                """
                INSERT INTO writing_v2.chapter_revision(
                  chapter_revision_id, article_id, chapter_index, revision_no, status,
                  title, body_markdown, content_hash, source_basis, source_action_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                chapterRevisionId,
                articleId,
                chapterIndex,
                revisionNo,
                ChapterRevisionStatus.CURRENT.name(),
                title,
                bodyMarkdown,
                contentHash,
                sourceBasis,
                sourceActionId);
    }

    @Override
    public void lockArticleForUpdate(UUID articleId) {
        jdbc.queryForObject(
                "SELECT article_id FROM writing_v2.article WHERE article_id = ? FOR UPDATE",
                UUID.class,
                articleId);
    }

    @Override
    public void supersedeCurrentChapter(UUID articleId, int chapterIndex) {
        jdbc.update(
                """
                UPDATE writing_v2.chapter_revision
                SET status = ?
                WHERE article_id = ? AND chapter_index = ? AND status = ?
                """,
                ChapterRevisionStatus.SUPERSEDED.name(),
                articleId,
                chapterIndex,
                ChapterRevisionStatus.CURRENT.name());
    }

    @Override
    public long nextChapterRevisionNo(UUID articleId, int chapterIndex) {
        Long next =
                jdbc.queryForObject(
                        """
                        SELECT COALESCE(MAX(revision_no), 0) + 1
                        FROM writing_v2.chapter_revision
                        WHERE article_id = ? AND chapter_index = ?
                        """,
                        Long.class,
                        articleId,
                        chapterIndex);
        return next == null ? 1L : next;
    }

    @Override
    public List<ChapterView> listCurrentChapters(UUID articleId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT * FROM writing_v2.chapter_revision
                        WHERE article_id = ? AND status = ?
                        ORDER BY chapter_index ASC
                        """,
                        articleId,
                        ChapterRevisionStatus.CURRENT.name());
        List<ChapterView> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(mapChapter(row));
        }
        return out;
    }

    @Override
    public Optional<ChapterView> findCurrentChapter(UUID articleId, int chapterIndex) {
        try {
            return Optional.of(
                    mapChapter(
                            jdbc.queryForMap(
                                    """
                                    SELECT * FROM writing_v2.chapter_revision
                                    WHERE article_id = ? AND chapter_index = ? AND status = ?
                                    """,
                                    articleId,
                                    chapterIndex,
                                    ChapterRevisionStatus.CURRENT.name())));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ChapterView> findBySourceActionId(UUID sourceActionId) {
        if (sourceActionId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    mapChapter(
                            jdbc.queryForMap(
                                    """
                                    SELECT * FROM writing_v2.chapter_revision
                                    WHERE source_action_id = ?
                                    """,
                                    sourceActionId)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void setProjectCurrentArticle(UUID projectId, UUID articleId) {
        jdbc.update(
                """
                UPDATE writing_v2.writing_project
                SET current_article_id = ?, project_version = project_version + 1, updated_at = NOW()
                WHERE project_id = ?
                """,
                articleId,
                projectId);
    }

    private static ArticleView mapArticle(Map<String, Object> row) {
        return new ArticleView(
                (UUID) row.get("article_id"),
                (UUID) row.get("project_id"),
                (UUID) row.get("plan_revision_id"),
                (String) row.get("status"),
                (String) row.get("title"),
                ((Number) row.get("chapter_count")).intValue(),
                ((Number) row.get("completed_chapter_count")).intValue(),
                Boolean.TRUE.equals(row.get("external_sources_used")),
                (String) row.get("evidence_basis"),
                toInstant(row.get("completed_at")));
    }

    private static ChapterView mapChapter(Map<String, Object> row) {
        return new ChapterView(
                (UUID) row.get("chapter_revision_id"),
                (UUID) row.get("article_id"),
                ((Number) row.get("chapter_index")).intValue(),
                ((Number) row.get("revision_no")).longValue(),
                (String) row.get("status"),
                (String) row.get("title"),
                (String) row.get("body_markdown"),
                (String) row.get("content_hash"),
                (String) row.get("source_basis"),
                toInstant(row.get("created_at")),
                (UUID) row.get("source_action_id"));
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        throw new IllegalStateException("Unsupported timestamp type: " + value.getClass());
    }
}
