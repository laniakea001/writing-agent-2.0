package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.writingagent.v2.domain.DocumentJsonSupport.*;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.capability.*;
import com.writingagent.v2.agent.main.WorkspaceMainAgent;
import com.writingagent.v2.agent.chapter.ChapterAgentRuntime;
import com.writingagent.v2.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real Tool callbacks / durable dispatch / confirmation; only the remote model boundary is substituted. */
@SpringBootTest(properties="writing.batch.dispatch-enabled=true")
@EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class BatchBlockerRegressionTest {
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){com.writingagent.v2.support.TestDatabase.register(r);}
 @Autowired ProjectApplicationService projects; @Autowired WritingCapability writing;
 @Autowired WorkspaceConversations conversations; @Autowired StageAiRuns stages; @Autowired BatchRewriteWorkflow batches;
 @MockitoBean WorkspaceMainAgent mainModel; @MockitoBean ChapterAgentRuntime chapterModel;
 record Fixture(UUID project,UUID conversation,List<UUID> keys) {}
 private Fixture fixture(){
  UUID ws=(UUID)projects.createWorkspace("批量阻塞修复隔离","local-fixture").get("workspaceId");
  UUID p=(UUID)projects.createProject(ws,"六章测试","验证范围、调度和标记").get("projectId");
  UUID c=(UUID)conversations.create(ws).get("conversationId");conversations.select(c,p);
  var payload=JSON.createObjectNode();var chapters=payload.putArray("chapters");for(int n=1;n<=6;n++)chapters.addObject().put("title","章节"+n);
  var plan=writing.savePlan(p,new WritingCommands.PlanSave("plan",1L,null,payload));
  var card=writing.prepare(p,new WritingCommands.ProposalPrepare("confirm","CONFIRM_PLAN","HTTP",(UUID)plan.get("planRevisionId"),2L,null));
  writing.decide((UUID)card.get("proposalId"),new WritingCommands.ProposalDecision("yes","APPROVE"));
  List<UUID> keys=new ArrayList<>();JSON.valueToTree(writing.article(p)).path("chapters").forEach(n->keys.add(UUID.fromString(n.path("chapterKey").asText())));
  when(chapterModel.run(any(),any(),any(),anyInt(),anyString(),anyString(),anyList(),anyList(),anyString(),nullable(String.class),any(),any())).thenAnswer(a->{
   var o=new ChapterOutcome();String instruction=a.getArgument(8);o.bodyMarkdown="测试改写第"+a.getArgument(3)+"章\n\n"+(instruction.contains("@#003")?"@#003":instruction.contains("@#002")?"@#002":"正文");
   return new ChapterAgentRuntime.AgentRunResult(o,0,List.of(),"",1,"");
  });
  return new Fixture(p,c,keys);
 }
 @Test void assistantGeneratesOnlyTwoSelectedChapters() throws Exception {
  var f=fixture();when(mainModel.run(any(),any(),anyString(),anyList(),anyString(),any())).thenAnswer(a->{
   WorkspaceMainAgent.Tools tools=a.getArgument(5);tools.invoke("read_writing_project","read",Map.of());
   tools.invoke("draft_selected_chapters","selected",Map.of("chapterIndexes",List.of(2,3),"instruction","生成第2章和第3章"));return "完成";
  });
  var run=stages.submitConversation(f.conversation(),new StageAiRuns.ConversationTurn("selected","生成第2章和第3章"));waitRun(UUID.fromString(run.get("rootRunId").toString()));
  assertNotNull(writing.chapter(f.project(),f.keys().get(1)).get("chapterRevisionId"));assertNotNull(writing.chapter(f.project(),f.keys().get(2)).get("chapterRevisionId"));
  for(int n:List.of(0,3,4,5))assertNull(writing.chapter(f.project(),f.keys().get(n)).get("chapterRevisionId"));
 }
 @Test void stageBatchStartsWithoutRestartAndConfirmsEachMarkedChapterSeparately() throws Exception {
  var f=fixture();var batch=stages.submitBatch(f.project(),new BatchRewriteWorkflow.Request("stage",List.of(f.keys().get(3),f.keys().get(4)),"修改第4章和第5章，结尾添加@#003"));
  var first=waitCandidate(batch.batchId(),0);assertEquals("PENDING",first.items().get(1).status().name());
  assertNull(writing.chapter(f.project(),f.keys().get(3)).get("chapterRevisionId"));
  assertTrue(JSON.valueToTree(writing.proposal(first.items().get(0).proposalId())).path("targetSnapshot").path("candidate").path("contentJson").toString().contains("@#003"));
  writing.decide(first.items().get(0).proposalId(),new WritingCommands.ProposalDecision("approve4","APPROVE"));
  var second=waitCandidate(batch.batchId(),1);assertNull(writing.chapter(f.project(),f.keys().get(4)).get("chapterRevisionId"));
  writing.decide(second.items().get(1).proposalId(),new WritingCommands.ProposalDecision("approve5","APPROVE"));
  for(int n:List.of(3,4))assertTrue(JSON.valueToTree(writing.chapter(f.project(),f.keys().get(n))).path("contentJson").toString().contains("@#003"));
  for(int n:List.of(0,1,2,5))assertNull(writing.chapter(f.project(),f.keys().get(n)).get("chapterRevisionId"));
 }
 @Test void assistantPreservesOriginalMarkerRatherThanModelsParaphrase() throws Exception {
  var f=fixture();when(mainModel.run(any(),any(),anyString(),anyList(),anyString(),any())).thenAnswer(a->{
   WorkspaceMainAgent.Tools tools=a.getArgument(5);tools.invoke("read_writing_project","read",Map.of());
   tools.invoke("rewrite_chapters","rewrite",Map.of("chapterIndexes",List.of(2,3),"instruction","润色一下"));return "任务已登记";
  });
  var run=stages.submitConversation(f.conversation(),new StageAiRuns.ConversationTurn("rewrite","修改第2章和第3章，结尾添加@#002"));waitRun(UUID.fromString(run.get("rootRunId").toString()));
  var batch=batches.active(f.project()).orElseThrow();assertEquals("修改第2章和第3章，结尾添加@#002",batch.instruction());
  var first=waitCandidate(batch.batchId(),0);assertTrue(JSON.valueToTree(writing.proposal(first.items().get(0).proposalId())).path("targetSnapshot").path("candidate").path("contentJson").toString().contains("@#002"));
  batches.control(batch.batchId(),new BatchRewriteWorkflow.Control("stop",first.version(),"STOP"));
 }
 @Test void stageSelectionAndWrittenChapterReferencesCannotConflict(){
  var f=fixture();assertEquals("CHAPTER_SCOPE_MISMATCH",assertThrows(WritingRuleException.class,()->stages.submitBatch(f.project(),new BatchRewriteWorkflow.Request("conflict",List.of(f.keys().get(3),f.keys().get(4)),"修改第2章和第3章，结尾添加@#002"))).code());
  assertTrue(batches.active(f.project()).isEmpty());
 }
 @Test void assistantCannotShrinkExplicitMultiChapterRewriteToOneChapter() throws Exception {
  var f=fixture();when(mainModel.run(any(),any(),anyString(),anyList(),anyString(),any())).thenAnswer(a->{
   WorkspaceMainAgent.Tools tools=a.getArgument(5);tools.invoke("read_writing_project","read",Map.of());
   assertEquals("CHAPTER_SCOPE_MISMATCH",assertThrows(WritingRuleException.class,()->tools.invoke("rewrite_target_chapter","single",Map.of("chapterIndex",2,"instruction","润色"))).code());
   return "必须使用完整的第2、3章批量目标";
  });
  var run=stages.submitConversation(f.conversation(),new StageAiRuns.ConversationTurn("no-shrink","修改第2章和第3章，结尾添加@#002"));waitRun(UUID.fromString(run.get("rootRunId").toString()));
  verifyNoInteractions(chapterModel);
 }
 private com.writingagent.v2.application.port.BatchRewritePort.Batch waitCandidate(UUID batch,int index)throws Exception{
  long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
  while(System.nanoTime()<end){var b=batches.read(batch);if(b.items().get(index).status().name().equals("WAITING_CONFIRMATION"))return b;Thread.sleep(25);}throw new AssertionError("批量未在同一进程生成候选");
 }
 private void waitRun(UUID id)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(!Set.of("QUEUED","RUNNING").contains(stages.read(id).get("status")))return;Thread.sleep(25);}fail("助手未收口");}
}
