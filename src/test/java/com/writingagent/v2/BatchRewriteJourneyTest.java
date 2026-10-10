package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.*;
import static com.writingagent.v2.domain.DocumentJsonSupport.*;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.capability.*;
import java.util.*;
import java.util.concurrent.*;
import com.writingagent.v2.domain.WritingRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Caller-visible batch contract; isolated fixtures, no model, no cleanup of existing data. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class BatchRewriteJourneyTest {
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",com.writingagent.v2.support.TestDatabase::url);r.add("spring.flyway.clean-disabled",()->true);}
 @Autowired ProjectApplicationService projects;
 @Autowired WritingCapability writing;
 @Autowired BatchRewriteWorkflow batches;
 @Autowired com.writingagent.v2.application.port.AiRunPort runs;
 @Autowired org.springframework.transaction.support.TransactionTemplate tx;
 private UUID fixture(){
  UUID ws=(UUID)projects.createWorkspace("Batch rewrite isolated","local-fixture").get("workspaceId");
  UUID p=(UUID)projects.createProject(ws,"批量范围测试","三章").get("projectId");
  var saved=writing.savePlan(p,new WritingCommands.PlanSave("plan",1L,null,parse("{\"chapters\":[{\"title\":\"第一章\"},{\"title\":\"第二章\"},{\"title\":\"第三章\"}]}")));
  var card=writing.prepare(p,new WritingCommands.ProposalPrepare("plan-card","CONFIRM_PLAN","HTTP",(UUID)saved.get("planRevisionId"),2L,null));
  writing.decide((UUID)card.get("proposalId"),new WritingCommands.ProposalDecision("confirm","APPROVE"));
  return p;
 }
 @Test void submittingThreeChaptersIsDurableAndReplayDoesNotChangeTheArticle(){
  UUID p=fixture();var before=writing.article(p);var nodes=JSON.valueToTree(before).path("chapters");
  var keys=new ArrayList<UUID>();nodes.forEach(n->keys.add(UUID.fromString(n.path("chapterKey").asText())));
  var request=new BatchRewriteWorkflow.Request("batch-original",keys,"每章约100字，末尾章节编号-hjj1009");
  var first=batches.submit(p,request);var recovered=batches.read(first.batchId());var replay=batches.submit(p,request);
  assertEquals(first.batchId(),replay.batchId());assertEquals(3,recovered.items().size());
  assertEquals(List.of(1,2,3),recovered.items().stream().map(i->i.chapterIndex()).toList());
  assertEquals("每章约100字，末尾章节编号-hjj1009",recovered.instruction());
  assertEquals(before,writing.article(p));
 }
 @Test void concurrentSameRequestReturnsTheSameBatch() throws Exception {
  UUID p=fixture();var keys=new ArrayList<UUID>();JSON.valueToTree(writing.article(p)).path("chapters").forEach(n->keys.add(UUID.fromString(n.path("chapterKey").asText())));
  var request=new BatchRewriteWorkflow.Request("concurrent",keys,"三章润色");var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
  try{var one=pool.submit(()->{gate.await();return batches.submit(p,request);});var two=pool.submit(()->{gate.await();return batches.submit(p,request);});gate.countDown();assertEquals(one.get(15,TimeUnit.SECONDS).batchId(),two.get(15,TimeUnit.SECONDS).batchId());}finally{pool.shutdownNow();}
 }
 @Test void anotherBrowserCanDiscoverTheActiveBatchWithoutALocalActionKey(){
  UUID p=fixture();UUID chapter=UUID.fromString(JSON.valueToTree(writing.article(p)).path("chapters").get(0).path("chapterKey").asText());
  assertTrue(batches.active(p).isEmpty());
  var accepted=batches.submit(p,new BatchRewriteWorkflow.Request("cross-browser",List.of(chapter),"润色"));
  assertEquals(accepted.batchId(),batches.active(p).orElseThrow().batchId());
  batches.control(accepted.batchId(),new BatchRewriteWorkflow.Control("stop",accepted.version(),"STOP"));
  assertTrue(batches.active(p).isEmpty());
 }
 @Test void changedParametersAndAnotherBatchCannotReplaceTheAcceptedIntent(){
  UUID p=fixture();UUID chapter=UUID.fromString(JSON.valueToTree(writing.article(p)).path("chapters").get(0).path("chapterKey").asText());
  var first=batches.submit(p,new BatchRewriteWorkflow.Request("original",List.of(chapter),"原要求"));
  var changed=assertThrows(WritingRuleException.class,()->batches.submit(p,new BatchRewriteWorkflow.Request("original",List.of(chapter),"新要求")));assertEquals("IDEMPOTENCY_KEY_REUSED",changed.code());
  var second=assertThrows(WritingRuleException.class,()->batches.submit(p,new BatchRewriteWorkflow.Request("another",List.of(chapter),"其它要求")));assertEquals("BATCH_WRITE_BUSY",second.code());assertEquals("原要求",batches.read(first.batchId()).instruction());
 }
 private static com.fasterxml.jackson.databind.JsonNode doc(String text){var d=JSON.createObjectNode().put("type","doc");d.putArray("content").addObject().put("type","paragraph").putArray("content").addObject().put("type","text").put("text",text);return d;}
 @Test void approvalAndRejectionAdvanceOneItemWithoutWritingOtherChapters(){
  UUID p=fixture();var keys=new ArrayList<UUID>();JSON.valueToTree(writing.article(p)).path("chapters").forEach(n->keys.add(UUID.fromString(n.path("chapterKey").asText())));
  var batch=batches.submit(p,new BatchRewriteWorkflow.Request("sequential",keys,"逐章修改"));
  var before=writing.article(p);var first=batches.prepareCandidate(batch.batchId(),batch.items().get(0).itemId(),doc("第一章候选 1-hjj1009"),null);
  assertEquals(before,writing.article(p));
  writing.decide((UUID)first.get("proposalId"),new WritingCommands.ProposalDecision("apply-first","APPROVE"));
  var next=batches.read(batch.batchId());assertEquals("APPLIED",next.items().get(0).status().name());assertEquals("PENDING",next.items().get(1).status().name());
  var second=batches.prepareCandidate(batch.batchId(),next.items().get(1).itemId(),doc("不应保存的第二章"),null);
  writing.decide((UUID)second.get("proposalId"),new WritingCommands.ProposalDecision("skip-second","REJECT"));
  assertNull(writing.chapter(p,keys.get(1)).get("chapterRevisionId"));
  var third=batches.prepareCandidate(batch.batchId(),next.items().get(2).itemId(),doc("第三章候选 3-hjj1009"),null);
  writing.decide((UUID)third.get("proposalId"),new WritingCommands.ProposalDecision("apply-third","APPROVE"));
  assertEquals("COMPLETED_WITH_SKIPS",batches.read(batch.batchId()).status().name());
  assertEquals("第一章候选 1-hjj1009",writing.chapter(p,keys.get(0)).get("bodyMarkdown"));
  assertEquals("第三章候选 3-hjj1009",writing.chapter(p,keys.get(2)).get("bodyMarkdown"));
 }
 @Test void completedArticleRequiresOneReopenCardForTheEntireBatch(){
  UUID p=fixture();var keys=new ArrayList<UUID>();JSON.valueToTree(writing.article(p)).path("chapters").forEach(n->keys.add(UUID.fromString(n.path("chapterKey").asText())));
  for(UUID key:keys){var a=writing.article(p);writing.saveChapter(p,key,new WritingCommands.ChapterSave("seed-"+key,(UUID)a.get("planRevisionId"),null,((Number)a.get("articleVersion")).longValue(),doc("原章正文")));}
  var project=writing.project(p);var a=writing.article(p);var completion=writing.prepare(p,new WritingCommands.ProposalPrepare("complete-card","COMPLETE_ARTICLE","HTTP",null,((Number)project.get("projectVersion")).longValue(),((Number)a.get("articleVersion")).longValue()));
  writing.decide((UUID)completion.get("proposalId"),new WritingCommands.ProposalDecision("complete","APPROVE"));
  var before=writing.article(p);var batch=batches.submit(p,new BatchRewriteWorkflow.Request("completed-batch",keys,"全部润色"));
  assertEquals("WAITING_REOPEN",batch.status().name());assertNotNull(batch.reopenProposalId());
  var card=writing.proposal(batch.reopenProposalId());assertEquals(3,JSON.valueToTree(card.get("continuation")).path("targets").size());assertEquals(before,writing.article(p));
  writing.decide(batch.reopenProposalId(),new WritingCommands.ProposalDecision("reopen","APPROVE"));
  assertEquals("RUNNING",batches.read(batch.batchId()).status().name());
  assertEquals("原章正文",writing.chapter(p,keys.get(0)).get("bodyMarkdown"));
 }
 @Test void registeringTheNextGenerationRunClaimsOnlyOneItemAndCannotBeRepeated(){
  UUID p=fixture();var keys=new ArrayList<UUID>();JSON.valueToTree(writing.article(p)).path("chapters").forEach(n->keys.add(UUID.fromString(n.path("chapterKey").asText())));
  var batch=batches.submit(p,new BatchRewriteWorkflow.Request("one-run",keys,"逐章润色"));
  var run=batches.claimNextRun(batch.batchId()).orElseThrow();
  assertEquals("BATCH_REWRITE_CANDIDATE",run.request().path("operation").asText());
  assertEquals(keys.get(0).toString(),run.request().path("chapterKey").asText());
  assertTrue(batches.claimNextRun(batch.batchId()).isEmpty());
  var recovered=batches.read(batch.batchId());assertEquals(run.runId(),recovered.items().get(0).runId());
  assertEquals("GENERATING",recovered.items().get(0).status().name());assertEquals("PENDING",recovered.items().get(1).status().name());
 }
 @Test void onlyExplicitlyActivatedBatchesBecomeDispatchableAndClaimedRunsAreNeverRedispatched(){
  UUID p=fixture();UUID key=UUID.fromString(JSON.valueToTree(writing.article(p)).path("chapters").get(0).path("chapterKey").asText());
  var batch=batches.submit(p,new BatchRewriteWorkflow.Request("dispatch-opt-in",List.of(key),"仅正式提交任务可生成"));
  assertFalse(batches.dispatchableIds().contains(batch.batchId()));
  batches.activate(batch.batchId());
  assertTrue(batches.dispatchableIds().contains(batch.batchId()));
  batches.activate(batch.batchId());
  batches.claimNextRun(batch.batchId()).orElseThrow();
  assertFalse(batches.dispatchableIds().contains(batch.batchId()));
 }
 @Test void generatedCandidateMustBelongToTheClaimedItemRun(){
  UUID p=fixture();UUID key=UUID.fromString(JSON.valueToTree(writing.article(p)).path("chapters").get(0).path("chapterKey").asText());
  var batch=batches.submit(p,new BatchRewriteWorkflow.Request("result-run-binding",List.of(key),"本章润色"));
  var run=batches.claimNextRun(batch.batchId()).orElseThrow();var item=batch.items().get(0);
  var mismatch=assertThrows(WritingRuleException.class,()->batches.prepareCandidate(batch.batchId(),item.itemId(),doc("不可信结果"),UUID.randomUUID()));
  assertEquals("BATCH_RESULT_RUN_MISMATCH",mismatch.code());
  assertNull(batches.read(batch.batchId()).items().get(0).proposalId());
  var missing=assertThrows(WritingRuleException.class,()->batches.prepareCandidate(batch.batchId(),item.itemId(),doc("缺运行身份"),null));
  assertEquals("BATCH_RESULT_RUN_MISMATCH",missing.code());
  var card=batches.prepareCandidate(batch.batchId(),item.itemId(),doc("当前运行生成候选"),run.runId());
  assertEquals(card.get("proposalId"),batches.read(batch.batchId()).items().get(0).proposalId());
  assertNull(writing.chapter(p,key).get("chapterRevisionId"));
 }
 @Test void uncertainGenerationStopsTheBatchWithoutWritingOrDispatchingTheNextChapter(){
  UUID p=fixture();var keys=new ArrayList<UUID>();JSON.valueToTree(writing.article(p)).path("chapters").forEach(n->keys.add(UUID.fromString(n.path("chapterKey").asText())));
  var batch=batches.submit(p,new BatchRewriteWorkflow.Request("unknown-stop",keys,"逐章改写"));var run=batches.claimNextRun(batch.batchId()).orElseThrow();
  batches.generationFailed(batch.batchId(),run.runId(),true);
  var recovered=batches.read(batch.batchId());assertEquals("UNKNOWN",recovered.status().name());assertEquals("UNKNOWN",recovered.items().get(0).status().name());
  assertEquals("PENDING",recovered.items().get(1).status().name());assertTrue(batches.claimNextRun(batch.batchId()).isEmpty());assertNull(writing.chapter(p,keys.get(0)).get("chapterRevisionId"));
 }
 @Test void assistantBatchPreservesItsConversationAndCannotUseAForeignWorkspaceRun(){
  UUID p=fixture();UUID ws=(UUID)writing.project(p).get("workspaceId");UUID key=UUID.fromString(JSON.valueToTree(writing.article(p)).path("chapters").get(0).path("chapterKey").asText());
  var conv=runs.createConversation(ws);runs.selectProject(conv.conversationId(),p);var input=JSON.createObjectNode().put("operation","ASSISTANT_TURN");
  var source=tx.execute(s->runs.register(ws,p,conv.conversationId(),"source-batch","fixture",input));
  var batch=batches.submitForAgent(p,new BatchRewriteWorkflow.Request("assistant-batch",List.of(key),"第一章润色"),conv.conversationId(),source.runId());
  assertEquals("AGENT",batch.origin());assertEquals(conv.conversationId(),batch.conversationId());
  UUID other=fixture();UUID otherWs=(UUID)writing.project(other).get("workspaceId");UUID otherKey=UUID.fromString(JSON.valueToTree(writing.article(other)).path("chapters").get(0).path("chapterKey").asText());
  var rejected=assertThrows(WritingRuleException.class,()->batches.submitForAgent(other,new BatchRewriteWorkflow.Request("foreign-batch",List.of(otherKey),"越界"),conv.conversationId(),source.runId()));
  assertEquals("TARGET_SCOPE_REJECTED",rejected.code());
 }
 @Test void pauseResumeAndStopKeepFixedControlReceiptsAndStopLateCandidates(){
  UUID p=fixture();UUID key=UUID.fromString(JSON.valueToTree(writing.article(p)).path("chapters").get(0).path("chapterKey").asText());
  var batch=batches.submit(p,new BatchRewriteWorkflow.Request("controlled",List.of(key),"修改"));var run=batches.claimNextRun(batch.batchId()).orElseThrow();
  var pause=new BatchRewriteWorkflow.Control("pause",batch.version(),"PAUSE");var receipt=batches.control(batch.batchId(),pause);
  assertEquals("PAUSED",batches.read(batch.batchId()).status().name());assertTrue(batches.claimNextRun(batch.batchId()).isEmpty());
  var card=batches.prepareCandidate(batch.batchId(),batch.items().get(0).itemId(),doc("暂停时晚到候选"),run.runId());
  assertEquals("PAUSED",batches.read(batch.batchId()).status().name());assertEquals(List.of(),writing.proposal((UUID)card.get("proposalId")).get("allowedDecisions"));
  assertEquals(write(canonical(receipt)),write(canonical(batches.control(batch.batchId(),pause))));
  var current=batches.read(batch.batchId());batches.control(batch.batchId(),new BatchRewriteWorkflow.Control("resume",current.version(),"RESUME"));
  assertEquals("WAITING_CHAPTER_CONFIRMATION",batches.read(batch.batchId()).status().name());
  current=batches.read(batch.batchId());batches.control(batch.batchId(),new BatchRewriteWorkflow.Control("stop",current.version(),"STOP"));
  assertThrows(WritingRuleException.class,()->writing.decide((UUID)card.get("proposalId"),new WritingCommands.ProposalDecision("late-approve","APPROVE")));
  assertNull(writing.chapter(p,key).get("chapterRevisionId"));
 }
 @Test void activeBatchBlocksOtherAiWritesButManualEditIsPreservedAndPausesTheBatch(){
  UUID p=fixture();var a=writing.article(p);UUID key=UUID.fromString(JSON.valueToTree(a).path("chapters").get(0).path("chapterKey").asText());
  var batch=batches.submit(p,new BatchRewriteWorkflow.Request("write-isolation",List.of(key),"改写"));
  var rejected=assertThrows(WritingRuleException.class,()->writing.saveGeneratedChapter(p,key,new WritingCommands.ChapterSave("other-ai",(UUID)a.get("planRevisionId"),null,((Number)a.get("articleVersion")).longValue(),doc("其它AI稿"))));
  assertEquals("BATCH_WRITE_BUSY",rejected.code());
  writing.saveChapter(p,key,new WritingCommands.ChapterSave("manual-edit",(UUID)a.get("planRevisionId"),null,((Number)a.get("articleVersion")).longValue(),doc("手工稿必须保留")));
  assertTrue(batches.claimNextRun(batch.batchId()).isEmpty());assertEquals("PAUSED",batches.read(batch.batchId()).status().name());
  assertEquals("手工稿必须保留",writing.chapter(p,key).get("bodyMarkdown"));
 }
 @Test void reconciliationCannotDeclareALiveGenerationOwnerStopped(){
  UUID p=fixture();UUID key=UUID.fromString(JSON.valueToTree(writing.article(p)).path("chapters").get(0).path("chapterKey").asText());
  var batch=batches.submit(p,new BatchRewriteWorkflow.Request("live-owner",List.of(key),"改写"));var run=batches.claimNextRun(batch.batchId()).orElseThrow();
  var error=assertThrows(WritingRuleException.class,()->batches.reconcileStopped(batch.batchId(),ProcessHandle.current().pid()));
  assertEquals("OWNER_NOT_CONFIRMED_STOPPED",error.code());assertEquals("GENERATING",batches.read(batch.batchId()).items().get(0).status().name());assertEquals(run.runId(),batches.read(batch.batchId()).items().get(0).runId());
 }
}
