package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.writingagent.v2.application.ArticleApplicationService;
import com.writingagent.v2.application.port.ArticlePort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Zero-model: completeness gate rejects incomplete articles. */
class ArticleCompletenessTest {

    @Test
    void missingChapterIndexes_detectsGaps() {
        List<ArticlePort.ChapterView> chapters =
                List.of(
                        chapter(1, "a", "body1"),
                        chapter(3, "c", "body3"));
        assertEquals(List.of(2, 4), ArticleApplicationService.missingChapterIndexes(4, chapters));
    }

    @Test
    void missingChapterIndexes_treatsBlankAsMissing() {
        List<ArticlePort.ChapterView> chapters = List.of(chapter(1, "a", "   "));
        assertEquals(List.of(1), ArticleApplicationService.missingChapterIndexes(1, chapters));
    }

    private static ArticlePort.ChapterView chapter(int idx, String title, String body) {
        return new ArticlePort.ChapterView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                idx,
                1L,
                "CURRENT",
                title,
                body,
                "hash",
                "USER_GOAL_AND_MODEL_SYNTHESIS",
                Instant.now(),
                null);
    }
}
