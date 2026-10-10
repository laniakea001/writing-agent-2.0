package com.writingagent.v2.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.writingagent.v2.application.ActionLedgerService;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.TaskRunService;
import com.writingagent.v2.application.port.TaskRunPort;
import com.writingagent.v2.domain.RunStatus;
import com.writingagent.v2.domain.TaskStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** RG1 database contracts. A dedicated database is mandatory; never truncate user data. */
@SpringBootTest
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class RunJournalIntegrationTest {
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        com.writingagent.v2.support.TestDatabase.register(registry);
    }

    @Autowired ProjectApplicationService projects;
    @Autowired ActionLedgerService actions;
    @Autowired TaskRunService runs;
    @Autowired TaskRunPort query;
    @Autowired JdbcTemplate jdbc;
    @Autowired RunJournal journal;
    @Autowired TransactionTemplate tx;
    @Autowired RunReplay replay;
    @Autowired StoppedRunReconciler reconciler;
    @Autowired com.writingagent.v2.application.ArticleApplicationService articles;
    @Autowired com.writingagent.v2.application.port.ArticlePort articlePort;
    @Autowired com.writingagent.v2.application.port.PlanRevisionPort plans;
    @Autowired com.writingagent.v2.application.port.ProjectPort projectPort;
    @Autowired com.writingagent.v2.application.port.WritingDocumentPort documents;

    private UUID fixtureArticle(UUID root) {
        var scope=journal.scope(root);UUID plan=UUID.randomUUID();
        plans.insertDraft(plan,scope.projectId(),1,"{\"plan\":{\"chapterOutline\":[\"one\",\"two\"]}}",
                "fixture-hash","USER_GOAL_AND_MODEL_SYNTHESIS");
        projectPort.setCurrentPlanRevision(scope.projectId(),plan);
        assertEquals(1,plans.confirmDraftIfMatches(plan,1,"rg1-test"));
        UUID article = articles.ensureArticle(scope.projectId()).articleId();
        for (int index = 1; index <= 2; index++) {
            jdbc.update("INSERT INTO writing_v2.article_chapter(chapter_key,article_id,chapter_index,title) VALUES(?,?,?,?)",
                    UUID.randomUUID(), article, index, index == 1 ? "one" : "two");
        }
        return article;
    }

    @Test
    void actualChapterAndActionReceiptRollbackTogetherWithJournal_R311() {
        UUID root=newRun(),article=fixtureArticle(root);var scope=journal.scope(root);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{
            runs.guardBusinessCommit(root);
            UUID revision=saveCurrentChapter(scope.projectId(),"real test body",scope.actionId());
            actions.markResult(scope.actionId(),com.writingagent.v2.domain.ActionStatus.RESULT_RECORDED,null,true,null);
            runs.publishBusinessEvent(root,"CHAPTER_SAVED",revision.toString(),"saved");
            runs.requestRunSettlement(root,RunStatus.SUCCEEDED,0);
            throw new IllegalStateException("receipt commit injected failure");
        }));
        assertTrue(articlePort.listCurrentChapters(article).isEmpty());
        assertEquals("CLAIMED",actions.find(scope.projectId(),scope.clientActionId()).orElseThrow().status());
        assertFalse(journal.get(root).terminal());
        assertEquals(0,journal.get(root).latestSequence());
    }

    @Test
    void firstRealChapterSurvivesSecondChildFailureButArticleIsNotComplete_R312() {
        UUID root=newRun(),article=fixtureArticle(root);var scope=journal.scope(root);
        UUID task=jdbc.queryForObject("SELECT task_id FROM writing_v2.agent_run WHERE run_id=?",UUID.class,root);
        UUID child=journal.create(task,fixtureAction(root),root,"chapter-tool").runId();
        tx.executeWithoutResult(s->{
            runs.guardBusinessCommit(child);
            UUID revision=saveCurrentChapter(scope.projectId(),"first chapter",scope.actionId());
            runs.publishBusinessEvent(child,"CHAPTER_SAVED",revision.toString(),"saved");
            runs.requestRunSettlement(child,RunStatus.SUCCEEDED,0);
        });
        UUID failed=journal.create(task,fixtureAction(root),root,"second-tool").runId();
        journal.settle(failed,RunStatus.FAILED,0,"MODEL_UNAVAILABLE",false);
        tx.executeWithoutResult(s->runs.requestRunSettlement(root,RunStatus.FAILED,0));
        assertEquals(1,articlePort.listCurrentChapters(article).size());
        assertEquals("PARTIAL",articlePort.findById(article).orElseThrow().status());
        assertEquals(true,journal.page(root,0,256).get(0).safePayload().get("resultMayExist"));
        assertThrows(com.writingagent.v2.application.ArticleApplicationService.IncompleteArticleException.class,
                ()->articles.completeArticle(scope.projectId(),article));
    }

    @Test
    void failedRootWithSavedChildArtifactKeepsPartialResultFact_R312() {
        UUID root=newRun();
        UUID task=jdbc.queryForObject("SELECT task_id FROM writing_v2.agent_run WHERE run_id=?",UUID.class,root);
        UUID action=journal.scope(root).actionId();
        UUID child=journal.create(task,fixtureAction(root),root,"real-call").runId();
        journal.append(child,event("saved","ARTIFACT_SAVED",null));
        journal.settle(child,RunStatus.SUCCEEDED,2,null,true);
        tx.executeWithoutResult(s->runs.requestRunSettlement(root,RunStatus.FAILED,2));
        var terminal=journal.page(root,0,256).get(0);
        assertEquals(true,terminal.safePayload().get("resultMayExist"));
        assertEquals(RunStatus.FAILED,journal.get(root).status());
    }

    @Test
    void explicitStoppedOwnerRecoveryCannotSealLiveOwnerAndNeverRerunsModel_R319() {
        UUID run = newRun();
        assertThrows(RunContractException.class,
                () -> reconciler.reconcile(run, ProcessHandle.current().pid()));
        assertFalse(journal.get(run).terminal());
        long deadPid = Integer.MAX_VALUE;
        assertTrue(ProcessHandle.of(deadPid).isEmpty(), "test requires an absent pid");
        var recovered = reconciler.reconcile(run, deadPid);
        assertEquals(RunStatus.UNKNOWN, recovered.status());
        var scope = journal.scope(run);
        assertEquals("UNKNOWN_GENERATION", actions.find(scope.projectId(),scope.clientActionId()).orElseThrow().status());
        assertEquals(1, recovered.terminalSequence());
        assertEquals(recovered, reconciler.reconcile(run, deadPid));
    }

    private UUID newRun() {
        UUID workspace = (UUID) projects.createWorkspace("RG1 isolated", "rg1-test").get("workspaceId");
        var project = projects.createProject(workspace, "RG1", "Test event contracts only");
        UUID projectId = (UUID) project.get("projectId");
        UUID conversationId = (UUID) project.get("conversationId");
        // The journal fixture includes the workspace scope required by the current schema.
        UUID action = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO writing_v2.action_ledger
                  (action_id, workspace_id, project_id, conversation_id, scope_key,
                   client_action_id, request_digest, operation, status)
                VALUES (?, ?, ?, ?, ?, ?, 'fixture', 'RG1_TEST', 'CLAIMED')
                """, action, workspace, projectId, conversationId,
                "project:" + projectId, "rg1-" + UUID.randomUUID());
        UUID task = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO writing_v2.writing_task
                  (task_id, workspace_id, project_id, conversation_id, status)
                VALUES (?, ?, ?, ?, 'RUNNING')
                """, task, workspace, projectId, conversationId);
        UUID run = journal.create(task, action, null, null).runId();
        actions.bindTaskRun(action, conversationId, task, run);
        return run;
    }

    @Test
    void duplicateToolEventReturnsOriginalSequenceWithoutLeavingGap_R305() {
        UUID run = newRun();
        var start = event("start-1", "TOOL_CALL_STARTED", "call-1");
        assertEquals(1, journal.append(run, start));
        assertEquals(1, journal.append(run, start));
        assertEquals(2, journal.append(run, event("end-1", "TOOL_FINISHED", "call-1")));
        assertEquals(java.util.List.of(1L, 2L), journal.page(run, 0, 256).stream()
                .map(RunJournal.Event::sequence).toList());
    }

    @Test
    void applicationRunUsesJournalAndSealsOnlyAfterItsArtifactTransaction() {
        UUID fixture = newRun();
        UUID task = jdbc.queryForObject("SELECT task_id FROM writing_v2.agent_run WHERE run_id=?", UUID.class, fixture);
        UUID action = fixtureAction(fixture);
        UUID applicationRun = runs.createRun(task, action, RunStatus.RUNNING);
        assertEquals(2, journal.get(applicationRun).protocolVersion());
        tx.executeWithoutResult(s -> {
            runs.requestRunSettlement(applicationRun, RunStatus.SUCCEEDED, 0);
            assertFalse(journal.get(applicationRun).terminal(), "requested success is not yet a durable terminal");
            runs.publishBusinessEvent(applicationRun, "PLAN_DRAFT_SAVED", "plan-revision-test", "draft_saved");
        });
        var events = journal.page(applicationRun, 0, 256);
        assertEquals(java.util.List.of("ARTIFACT_SAVED", "RUN_TERMINAL"), events.stream()
                .map(RunJournal.Event::outputType).toList());
        assertTrue(journal.get(applicationRun).terminal());
    }

    @Test
    void requestedSuccessAndArtifactBothRollbackWhenBusinessTransactionFails() {
        UUID run = newRun();
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            runs.requestRunSettlement(run, RunStatus.SUCCEEDED, 1);
            runs.publishBusinessEvent(run, "CHAPTER_SAVED", "rolled-back-revision", "saved");
            throw new IllegalStateException("injected persistence failure");
        }));
        assertFalse(journal.get(run).terminal());
        assertEquals(java.util.List.of(), journal.page(run, 0, 256));
    }

    @Test
    void failedInsertDoesNotConsumeSequence_R306() {
        UUID run = newRun();
        jdbc.execute("ALTER TABLE writing_v2.run_event ADD CONSTRAINT rg1_insert_fail CHECK(raw_event_type IS DISTINCT FROM 'INJECT_INSERT_FAILURE')");
        try {
            assertThrows(RuntimeException.class, () -> journal.append(run,
                    new RunJournal.Draft("bad", "INJECT_INSERT_FAILURE", "INTERNAL", null, null, false, java.util.Map.of())));
        } finally { jdbc.execute("ALTER TABLE writing_v2.run_event DROP CONSTRAINT rg1_insert_fail"); }
        assertEquals(0, journal.get(run).latestSequence());
        assertEquals(1, journal.append(run, event("good", "MODEL_STARTED", null)));
    }

    private UUID saveCurrentChapter(UUID project, String body, UUID action) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var doc = mapper.valueToTree(java.util.Map.of("type", "doc", "content",
                java.util.List.of(java.util.Map.of("type", "paragraph", "content",
                        java.util.List.of(java.util.Map.of("type", "text", "text", body))))));
        var encoded = new com.writingagent.v2.domain.document.ChapterDocumentCodec().encode(doc.toString(), true);
        var article = documents.article(project, true).orElseThrow();
        var chapter = documents.chapters(article.id()).get(0);
        var saved = documents.saveChapter(article, chapter, action, doc, encoded.bodyMarkdown(),
                encoded.documentHash(), com.writingagent.v2.application.support.RequestDigests.sha256(encoded.bodyMarkdown()),
                "USER_GOAL_AND_MODEL_SYNTHESIS");
        documents.refreshArticle(article.id(), 1, "PARTIAL");
        return saved.revisionId();
    }

    /** The current schema requires one independently claimed action per run. */
    private UUID fixtureAction(UUID runId) {
        var scope = journal.scope(runId);
        return actions.claim(scope.projectId(), "journal-fixture-" + UUID.randomUUID(),
                "fixture", "RG1_TEST").actionId();
    }

    private static RunJournal.Draft event(String key, String type, String toolId) {
        return new RunJournal.Draft(key, type, type, toolId, null, true, java.util.Map.of("label", "test"));
    }

    @Test
    void terminalIsOnceAndSealsAllDescendants_R310() {
        UUID root = newRun();
        UUID task = jdbc.queryForObject("SELECT task_id FROM writing_v2.agent_run WHERE run_id=?", UUID.class, root);
        UUID action = jdbc.queryForObject("SELECT action_id FROM writing_v2.agent_run WHERE run_id=?", UUID.class, root);
        UUID child = journal.create(task, fixtureAction(root), root, "real-tool-call").runId();
        assertEquals("CHILD_RUN_NOT_TERMINAL", assertThrows(RunContractException.class,
                () -> journal.settle(root, RunStatus.SUCCEEDED, 0, null, false)).code());
        journal.settle(child, RunStatus.SUCCEEDED, 2, null, true);
        var terminal = journal.settle(root, RunStatus.SUCCEEDED, 3, null, true);
        assertEquals(java.util.Map.of(root, 1L, child, 1L), terminal.terminalManifest());
        assertEquals(terminal, journal.settle(root, RunStatus.FAILED, 9, "late", false));
        assertEquals("RUN_ALREADY_TERMINAL", assertThrows(RunContractException.class,
                () -> journal.append(child, event("late", "MODEL_STARTED", null))).code());
        assertThrows(RunContractException.class, () -> journal.create(task, action, root, "late-child"));
    }

    @Test
    void duplicateProducerKeyCannotChangePayloadOrConsumeSequence_R305() {
        UUID root = newRun();
        journal.append(root, event("key", "MODEL_STARTED", null));
        assertEquals("EVENT_KEY_REUSED", assertThrows(RunContractException.class,
                () -> journal.append(root, event("key", "MODEL_FINISHED", null))).code());
        assertEquals(1, journal.get(root).latestSequence());
    }

    @Test
    void businessGuardRequiresTransactionAndRejectsSealedRun_R313() {
        UUID run = newRun();
        assertThrows(RunContractException.class, () -> journal.guardBusinessCommit(run));
        tx.executeWithoutResult(s -> journal.guardBusinessCommit(run));
        journal.settle(run, RunStatus.SUCCEEDED, 0, null, false);
        assertThrows(RunContractException.class, () -> tx.executeWithoutResult(s -> journal.guardBusinessCommit(run)));
    }

    @Test
    void unknownRootSealsUnresolvedChildrenBeforeManifest_R310() {
        UUID root = newRun();
        UUID task = jdbc.queryForObject("SELECT task_id FROM writing_v2.agent_run WHERE run_id=?", UUID.class, root);
        UUID action = jdbc.queryForObject("SELECT action_id FROM writing_v2.agent_run WHERE run_id=?", UUID.class, root);
        UUID child = journal.create(task, fixtureAction(root), root, "call").runId();
        var terminal = journal.settle(root, RunStatus.FAILED, 1, "execution interrupted", false);
        assertEquals(RunStatus.UNKNOWN, terminal.status());
        assertEquals(RunStatus.UNKNOWN, journal.get(child).status());
        assertEquals(java.util.Map.of(root, 1L, child, 1L), terminal.terminalManifest());
    }

    @Test
    void replayPagesWithoutSkippingInternalEventsAndRejectsGaps_R309_R315() {
        UUID root = newRun();
        for (int n=1; n<=257; n++) journal.append(root,
                new RunJournal.Draft("page-"+n,"fixture","INTERNAL",null,null,false,java.util.Map.of()));
        assertEquals(256, journal.page(root, 0, 256).size());
        assertEquals(257, journal.page(root, 256, 256).get(0).sequence());
        assertThrows(RunContractException.class, () -> journal.page(root, 258, 256));
        jdbc.update("DELETE FROM writing_v2.run_event WHERE run_id=? AND seq=2", root);
        assertEquals("EVENT_GAP", assertThrows(RunContractException.class,
                () -> journal.page(root,0,256)).code());
    }

    @Test
    void replayWaitsForEveryTerminalTailAndNeverPublishesInternalContent_R308_R309() {
        UUID root=newRun();
        var scope=journal.scope(root);
        for(int n=1;n<=257;n++) journal.append(root,
                new RunJournal.Draft("internal-"+n,"INTERNAL","INTERNAL",null,null,false,java.util.Map.of()));
        journal.settle(root,RunStatus.SUCCEEDED,0,null,false);
        var first=replay.next(scope.projectId(),scope.conversationId(),root,"");
        assertFalse(first.complete());
        assertTrue(first.deliveries().isEmpty());
        var tail=replay.next(scope.projectId(),scope.conversationId(),root,first.cursor());
        assertTrue(tail.complete());
        assertEquals(1,tail.deliveries().size());
        assertEquals("RUN_TERMINAL",tail.deliveries().get(0).event().outputType());
        assertThrows(RunContractException.class,() -> replay.next(UUID.randomUUID(),scope.conversationId(),root,""));
        UUID another=newRun();
        var otherScope=journal.scope(another);
        assertThrows(RunContractException.class,() -> replay.next(otherScope.projectId(),otherScope.conversationId(),another,tail.cursor()));
    }

    @Test
    void concurrentDuplicateAppendsHaveOneSequenceAndOneReceipt_R305() throws Exception {
        UUID root=newRun();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var jobs=new java.util.ArrayList<java.util.concurrent.Callable<Long>>();
            for(int n=0;n<8;n++) jobs.add(() -> journal.append(root,event("same","TOOL_FINISHED","t1")));
            for(var result:pool.invokeAll(jobs)) assertEquals(1L,result.get());
            assertEquals(1,journal.get(root).latestSequence());
            assertEquals(1,journal.page(root,0,256).size());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void lateBusinessResultCannotBeCommittedAndOuterTransactionRollsJournalBack_R311() {
        UUID root=newRun();
        assertThrows(IllegalStateException.class,() -> tx.executeWithoutResult(s -> {
            journal.guardBusinessCommit(root);
            journal.append(root,event("artifact","ARTIFACT_SAVED",null));
            journal.settle(root,RunStatus.SUCCEEDED,2,null,true);
            throw new IllegalStateException("business receipt failed");
        }));
        assertEquals(0,journal.get(root).latestSequence());
        assertFalse(journal.get(root).terminal());
        journal.settle(root,RunStatus.UNKNOWN,2,"PERSIST_UNKNOWN",true);
        assertThrows(RunContractException.class,() -> tx.executeWithoutResult(s -> journal.guardBusinessCommit(root)));
    }

    @Test
    void concurrentTerminalContendersReturnOneFixedTerminal_R310_R317() throws Exception {
        UUID root=newRun();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try {
            var jobs=java.util.List.<java.util.concurrent.Callable<RunJournal.Run>>of(
                () -> { barrier.await(); return journal.settle(root,RunStatus.SUCCEEDED,2,null,true); },
                () -> { barrier.await(); return journal.settle(root,RunStatus.FAILED,1,"failed",false); });
            var results=pool.invokeAll(jobs);
            assertEquals(results.get(0).get(),results.get(1).get());
            assertEquals(1,journal.page(root,0,256).size());
            assertEquals(1,journal.get(root).terminalSequence());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void lateChildRegistrationAndAppendCannotCrossRootSeal_R317() throws Exception {
        UUID root=newRun();
        UUID task=jdbc.queryForObject("SELECT task_id FROM writing_v2.agent_run WHERE run_id=?",UUID.class,root);
        UUID action=jdbc.queryForObject("SELECT action_id FROM writing_v2.agent_run WHERE run_id=?",UUID.class,root);
        UUID child=journal.create(task,fixtureAction(root),root,"t1").runId();
        journal.settle(child,RunStatus.SUCCEEDED,0,null,false);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try {
            var seal=pool.submit(() -> { barrier.await(); return journal.settle(root,RunStatus.SUCCEEDED,0,null,false); });
            var add=pool.submit(() -> { barrier.await(); try {
                return journal.append(root,event("racing","MODEL_STARTED",null));
            } catch(RunContractException rejected) { assertEquals("RUN_ALREADY_TERMINAL",rejected.code()); return -1L; }});
            var terminal=seal.get();
            long sequence=add.get();
            assertTrue(sequence==-1 || sequence<terminal.terminalSequence());
            assertEquals(journal.get(root).latestSequence(),terminal.terminalSequence());
            assertThrows(RunContractException.class,() -> journal.create(task,action,root,"late"));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void legacyRunRemainsReadOnlyAndHistoricalSequenceIsNotRewritten_R316() {
        UUID v2=newRun();
        UUID task=jdbc.queryForObject("SELECT task_id FROM writing_v2.agent_run WHERE run_id=?",UUID.class,v2);
        UUID action=jdbc.queryForObject("SELECT action_id FROM writing_v2.agent_run WHERE run_id=?",UUID.class,v2);
        UUID legacy=UUID.randomUUID();
        query.insertRun(legacy,task,fixtureAction(v2),RunStatus.RUNNING);
        runs.appendEvent(legacy,"MODEL_REQUEST",null,"legacy","historical");
        jdbc.update("UPDATE writing_v2.run_event SET seq=3 WHERE run_id=?",legacy);
        assertEquals(1,journal.get(legacy).protocolVersion());
        assertEquals("LEGACY_RUN_READ_ONLY",assertThrows(RunContractException.class,
                () -> journal.append(legacy,event("new","MODEL_STARTED",null))).code());
        assertThrows(RunContractException.class,() -> journal.settle(legacy,RunStatus.SUCCEEDED,0,null,false));
        assertEquals(3,query.listExecutionEvents(legacy).get(0).sequence());
    }

    @Test
    void foreignProjectCannotAttachChildToRoot_R314() {
        UUID root=newRun(), foreign=newRun();
        UUID task=jdbc.queryForObject("SELECT task_id FROM writing_v2.agent_run WHERE run_id=?",UUID.class,foreign);
        UUID action=jdbc.queryForObject("SELECT action_id FROM writing_v2.agent_run WHERE run_id=?",UUID.class,foreign);
        assertEquals("RUN_SCOPE_MISMATCH",assertThrows(RunContractException.class,
                () -> journal.create(task,action,root,"t1")).code());
        assertEquals(1,journal.tree(root).size());
    }

    @Test
    void oldWriterCannotBypassV2Journal() {
        UUID run=newRun();
        assertEquals("V2_WRITE_REQUIRES_JOURNAL", assertThrows(RunContractException.class,
                () -> query.appendEvent(run,"MODEL_REQUEST",null,"legacy-bypass","invalid")).code());
        assertThrows(RunContractException.class,() -> runs.updateRunStatus(run,RunStatus.SUCCEEDED,0));
        assertEquals(0,journal.get(run).latestSequence());
        assertFalse(journal.get(run).terminal());
    }
}
