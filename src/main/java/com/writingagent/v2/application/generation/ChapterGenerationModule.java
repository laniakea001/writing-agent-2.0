package com.writingagent.v2.application.generation;

import com.writingagent.v2.application.ArticleChapterTurnService;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Shared chapter generate/rewrite entry for buttons and Main Agent Tools. Delegates to {@link
 * ArticleChapterTurnService} with an explicit {@link MessagePolicy}.
 */
@Service
public class ChapterGenerationModule {

    private final ArticleChapterTurnService chapters;

    public ChapterGenerationModule(ArticleChapterTurnService chapters) {
        this.chapters = chapters;
    }

    public Map<String, Object> generateOne(GenerationCommand cmd) {
        Objects.requireNonNull(cmd.chapterIndex(), "chapterIndex");
        return chapters.generateOneChapter(
                cmd.projectId(),
                cmd.conversationId(),
                cmd.clientActionId(),
                cmd.chapterIndex(),
                cmd.userMessage(),
                cmd.messages(),
                cmd.parentRunId(), cmd.parentToolCallId());
    }

    public Map<String, Object> rewrite(GenerationCommand cmd) {
        Objects.requireNonNull(cmd.chapterIndex(), "chapterIndex");
        return chapters.rewriteChapter(
                cmd.projectId(),
                cmd.conversationId(),
                cmd.clientActionId(),
                cmd.chapterIndex(),
                cmd.rewriteInstruction() != null ? cmd.rewriteInstruction() : cmd.userMessage(),
                cmd.messages(),
                cmd.parentRunId(), cmd.parentToolCallId());
    }
}
