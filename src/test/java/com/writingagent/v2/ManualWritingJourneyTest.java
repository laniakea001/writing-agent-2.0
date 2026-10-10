package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.capability.WritingCapability;
import com.writingagent.v2.application.capability.WritingCommands;
import com.writingagent.v2.domain.WritingRuleException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real Postgres, no model, no TRUNCATE; each test creates its own local fixture. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="WA2_D2_IT", matches="true")
class ManualWritingJourneyTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", com.writingagent.v2.support.TestDatabase::url);
        r.add("wa2.agent-state-dir", () -> "runtime/d2-test-agent-state");
        r.add("spring.flyway.clean-disabled",()->true);
    }
    @Autowired ProjectApplicationService projects;
    @Autowired WritingCapability writing;
    private static final ObjectMapper JSON=new ObjectMapper();

    @Test
    void manualWholeArticleCanBeWrittenConfirmedAndReadBackWithoutModel() throws Exception {
        UUID workspace=(UUID)projects.createWorkspace("D2-F01", "local-fixture").get("workspaceId");
        UUID project=(UUID)projects.createProject(workspace,"企业知识助手","两章手工文章").get("projectId");
        JsonNode plan=JSON.readTree("{\"chapters\":[{\"title\":\"定义与边界\",\"brief\":\"工具权限\"},{\"title\":\"落地与风险\"}]}");
        var draft=writing.savePlan(project,new WritingCommands.PlanSave("manual-plan",1L,null,plan));
        UUID planId=UUID.fromString(draft.get("planRevisionId").toString());
        var proposal=writing.prepare(project,new WritingCommands.ProposalPrepare("prepare-plan","CONFIRM_PLAN","HTTP",planId,2L,null));
        var confirmed=writing.decide(UUID.fromString(proposal.get("proposalId").toString()),new WritingCommands.ProposalDecision("approve-plan","APPROVE"));
        assertEquals("PLAN_CONFIRMED",confirmed.get("outcomeCode"));
        var article=writing.article(project);
        @SuppressWarnings("unchecked") var chapters=(java.util.List<Map<String,Object>>)article.get("chapters");
        for(int i=0;i<chapters.size();i++) {
            var current=writing.article(project);
            UUID key=UUID.fromString(chapters.get(i).get("chapterKey").toString());
            JsonNode doc=JSON.readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"CH"+(i+1)+"_BASE_1008 手写正文\"}]}]}");
            writing.saveChapter(project,key,new WritingCommands.ChapterSave("manual-chapter-"+i,planId,null,((Number)current.get("articleVersion")).longValue(),doc));
        }
        var ready=writing.article(project);
        assertEquals("READY_FOR_COMPLETE",ready.get("status"));
        var complete=writing.prepare(project,new WritingCommands.ProposalPrepare("prepare-complete","COMPLETE_ARTICLE","HTTP",null,((Number)writing.project(project).get("projectVersion")).longValue(),((Number)ready.get("articleVersion")).longValue()));
        var receipt=writing.decide(UUID.fromString(complete.get("proposalId").toString()),new WritingCommands.ProposalDecision("approve-complete","APPROVE"));
        assertEquals("ARTICLE_COMPLETED",receipt.get("outcomeCode"));
        assertEquals("COMPLETE",writing.article(project).get("status"));
        assertNotNull(writing.article(project).get("completionManifest"));
        assertEquals(receipt.get("recordedAt"),writing.decide(UUID.fromString(complete.get("proposalId").toString()),new WritingCommands.ProposalDecision("approve-complete","APPROVE")).get("recordedAt"));
        UUID firstKey=UUID.fromString(chapters.get(0).get("chapterKey").toString());
        var currentChapter=writing.chapter(project,firstKey);
        assertCode("ARTICLE_COMPLETE",()->writing.saveChapter(project,firstKey,new WritingCommands.ChapterSave(
                "edit-completed",planId,UUID.fromString(currentChapter.get("chapterRevisionId").toString()),
                ((Number)writing.article(project).get("articleVersion")).longValue(),document("MUST_NOT_EDIT_COMPLETED"))));
        assertEquals("COMPLETE",writing.article(project).get("status"));
    }

    @Test void concurrentSameKeySavesCreateOneRevisionAndReturnSameFixedReceipt() throws Exception {
        Fixture f=confirmedTwoChapterArticle();
        var article=writing.article(f.project());
        @SuppressWarnings("unchecked") var chapters=(java.util.List<Map<String,Object>>)article.get("chapters");
        UUID key=UUID.fromString(chapters.get(0).get("chapterKey").toString());
        var command=new WritingCommands.ChapterSave("parallel-same-key",f.plan(),null,
                ((Number)article.get("articleVersion")).longValue(),document("PARALLEL_SINGLE_REVISION"));
        var gate=new java.util.concurrent.CountDownLatch(1);
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Map<String,Object>> operation=()->{gate.await();return writing.saveChapter(f.project(),key,command);};
            var a=workers.submit(operation);var b=workers.submit(operation);gate.countDown();
            var first=a.get(10,java.util.concurrent.TimeUnit.SECONDS);var second=b.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(first.get("chapterRevisionId").toString(),second.get("chapterRevisionId").toString());
            assertEquals(first.get("recordedAt"),second.get("recordedAt"));
            assertEquals(1L,((Number)writing.chapter(f.project(),key).get("revisionNo")).longValue());
            assertEquals(((Number)article.get("articleVersion")).longValue()+1,
                    ((Number)writing.article(f.project()).get("articleVersion")).longValue());
        } finally {workers.shutdownNow();}
    }

    @Test void missingChapterAndStaleVersionNeverCompleteOrOverwrite() throws Exception {
        Fixture f=confirmedTwoChapterArticle();
        var article=writing.article(f.project());
        @SuppressWarnings("unchecked") var chapters=(java.util.List<Map<String,Object>>)article.get("chapters");
        UUID key=UUID.fromString(chapters.get(0).get("chapterKey").toString());
        long base=((Number)article.get("articleVersion")).longValue();
        var command=new WritingCommands.ChapterSave("chapter-one",f.plan(),null,base,document("FIRST_SAVED_1008"));
        var saved=writing.saveChapter(f.project(),key,command);
        var replay=writing.saveChapter(f.project(),key,command);
        // Public receipts carry JSON UUID strings; don't compare Java wrapper identity.
        assertEquals(saved.get("chapterRevisionId").toString(),replay.get("chapterRevisionId").toString());
        assertEquals(true,replay.get("replayed"));
        assertEquals(base+1,((Number)writing.article(f.project()).get("articleVersion")).longValue());
        assertCode("VERSION_CONFLICT",()->writing.saveChapter(f.project(),key,
                new WritingCommands.ChapterSave("stale-save",f.plan(),null,base,document("MUST_NOT_OVERWRITE"))));
        assertEquals(document("FIRST_SAVED_1008"),writing.chapter(f.project(),key).get("contentJson"));
        assertCode("IDEMPOTENCY_KEY_REUSED",()->writing.saveChapter(f.project(),key,
                new WritingCommands.ChapterSave("chapter-one",f.plan(),null,base,document("CHANGED_REQUEST"))));
        var current=writing.article(f.project());
        assertCode("ARTICLE_INCOMPLETE",()->writing.prepare(f.project(),new WritingCommands.ProposalPrepare(
                "incomplete","COMPLETE_ARTICLE","HTTP",null,projectVersion(f.project()),((Number)current.get("articleVersion")).longValue())));
        assertEquals("PARTIAL",writing.article(f.project()).get("status"));
    }

    @Test void emptyManualDocumentIsSavedButIsNotACompletedChapter() throws Exception {
        Fixture f=confirmedTwoChapterArticle();
        var a=writing.article(f.project());
        @SuppressWarnings("unchecked") var chapters=(java.util.List<Map<String,Object>>)a.get("chapters");
        UUID key=UUID.fromString(chapters.get(0).get("chapterKey").toString());
        writing.saveChapter(f.project(),key,new WritingCommands.ChapterSave("empty-save",f.plan(),null,
                ((Number)a.get("articleVersion")).longValue(),JSON.readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}")));
        assertEquals(0,writing.article(f.project()).get("completedChapterCount"));
        assertEquals("DRAFTING",writing.article(f.project()).get("status"));
    }

    @Test void editingPlanInvalidatesPreviouslyPreparedConfirmation() throws Exception {
        UUID w=(UUID)projects.createWorkspace("D2-stale-proposal","local-fixture").get("workspaceId");
        UUID p=(UUID)projects.createProject(w,"过期确认","手动").get("projectId");
        var payload=JSON.readTree("{\"chapters\":[{\"title\":\"原大纲\"}]}");
        UUID first=UUID.fromString(writing.savePlan(p,new WritingCommands.PlanSave("p1",1L,null,payload)).get("planRevisionId").toString());
        var pending=writing.prepare(p,new WritingCommands.ProposalPrepare("prepare-old","CONFIRM_PLAN","HTTP",first,2L,null));
        writing.savePlan(p,new WritingCommands.PlanSave("p2",2L,first,JSON.readTree("{\"chapters\":[{\"title\":\"新大纲\"}]}")));
        UUID proposal=UUID.fromString(pending.get("proposalId").toString());
        assertCode("STALE_CONFIRMATION",()->writing.decide(proposal,new WritingCommands.ProposalDecision("stale-approve","APPROVE")));
        assertEquals("STALE",writing.proposal(proposal).get("status"));
        assertNull(writing.workbench(p).get("article"));
    }

    private Fixture confirmedTwoChapterArticle() throws Exception {
        UUID w=(UUID)projects.createWorkspace("D2-negative","local-fixture").get("workspaceId");
        UUID p=(UUID)projects.createProject(w,"手动负例","两章").get("projectId");
        var payload=JSON.readTree("{\"chapters\":[{\"title\":\"第一章\"},{\"title\":\"第二章\"}]}");
        UUID plan=UUID.fromString(writing.savePlan(p,new WritingCommands.PlanSave("plan",1L,null,payload)).get("planRevisionId").toString());
        var proposal=writing.prepare(p,new WritingCommands.ProposalPrepare("prepare","CONFIRM_PLAN","HTTP",plan,2L,null));
        writing.decide(UUID.fromString(proposal.get("proposalId").toString()),new WritingCommands.ProposalDecision("approve","APPROVE"));
        return new Fixture(p,plan);
    }
    private long projectVersion(UUID p) {return ((Number)writing.project(p).get("projectVersion")).longValue();}
    private static JsonNode document(String text) {
        var root=JSON.createObjectNode();root.put("type","doc");var para=root.putArray("content").addObject();para.put("type","paragraph");
        var node=para.putArray("content").addObject();node.put("type","text");node.put("text",text);return root;
    }
    private static void assertCode(String expected,org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected,assertThrows(WritingRuleException.class,action).code());
    }
    private record Fixture(UUID project,UUID plan) {}
}
