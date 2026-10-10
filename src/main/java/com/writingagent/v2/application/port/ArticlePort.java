package com.writingagent.v2.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for article + chapter_revision. */
public interface ArticlePort {

    record ArticleView(
            UUID articleId,
            UUID projectId,
            UUID planRevisionId,
            String status,
            String title,
            int chapterCount,
            int completedChapterCount,
            boolean externalSourcesUsed,
            String evidenceBasis,
            Instant completedAt) {}

    record ChapterView(
            UUID chapterRevisionId,
            UUID articleId,
            int chapterIndex,
            long revisionNo,
            String status,
            String title,
            String bodyMarkdown,
            String contentHash,
            String sourceBasis,
            Instant createdAt,
            UUID sourceActionId) {}

    void insertArticle(
            UUID articleId,
            UUID projectId,
            UUID planRevisionId,
            String status,
            String title,
            int chapterCount,
            boolean externalSourcesUsed,
            String evidenceBasis);

    Optional<ArticleView> findByProjectId(UUID projectId);

    Optional<ArticleView> findById(UUID articleId);

    void updateArticleStatus(
            UUID articleId, String status, int completedChapterCount, Instant completedAt);

    void insertChapterCurrent(
            UUID chapterRevisionId,
            UUID articleId,
            int chapterIndex,
            long revisionNo,
            String title,
            String bodyMarkdown,
            String contentHash,
            String sourceBasis);

    void insertChapterCurrent(
            UUID chapterRevisionId,
            UUID articleId,
            int chapterIndex,
            long revisionNo,
            String title,
            String bodyMarkdown,
            String contentHash,
            String sourceBasis,
            UUID sourceActionId);

    /** Lock article row for chapter save / complete serialization (D0 H1-08). */
    void lockArticleForUpdate(UUID articleId);

    void supersedeCurrentChapter(UUID articleId, int chapterIndex);

    long nextChapterRevisionNo(UUID articleId, int chapterIndex);

    List<ChapterView> listCurrentChapters(UUID articleId);

    Optional<ChapterView> findCurrentChapter(UUID articleId, int chapterIndex);

    Optional<ChapterView> findBySourceActionId(UUID sourceActionId);

    void setProjectCurrentArticle(UUID projectId, UUID articleId);
}
