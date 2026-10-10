package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.writingagent.v2.domain.DocumentJsonSupport.*;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.capability.*;
import com.writingagent.v2.application.port.AiRunPort;
import com.writingagent.v2.agent.main.WorkspaceMainAgent;
import com.writingagent.v2.agent.chapter.ChapterAgentRuntime;
import com.writingagent.v2.domain.*;
import com.writingagent.v2.runtime.RunJournal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real application/PG/Tool callbacks; only remote model invocation adapters are substituted. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class PartialChapterRunTest {
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",com.writingagent.v2.support.TestDatabase::url);}
 @Autowired ProjectApplicationService projects; @Autowired WritingCapability writing;
 @Autowired WorkspaceConversations conversations; @Autowired StageAiRuns stages;
 @Autowired RunJournal journal; @Autowired AiRunPort runs;
 @MockitoBean WorkspaceMainAgent mainModel; @MockitoBean ChapterAgentRuntime chapterModel;

 @Test void ninthChapterFailurePreservesEightAndAllowsOnlyMissingChaptersToContinue() throws Exception {
  UUID ws=(UUID)projects.createWorkspace("20章失败回归","local-fixture").get("workspaceId");
  UUID p=(UUID)projects.createProject(ws,"20章隔离文章","回归测试").get("projectId");
  UUID conv=(UUID)conversations.create(ws).get("conversationId");conversations.select(conv,p);
  var payload=JSON.createObjectNode();var outline=payload.putArray("chapters");for(int n=1;n<=20;n++)outline.addObject().put("title","章节"+n);
  var saved=writing.savePlan(p,new WritingCommands.PlanSave("plan",1L,null,payload));
  var pr=writing.project(p);var proposal=writing.prepare(p,new WritingCommands.ProposalPrepare("confirm","CONFIRM_PLAN","HTTP",UUID.fromString(saved.get("planRevisionId").toString()),((Number)pr.get("projectVersion")).longValue(),null));
  writing.decide((UUID)proposal.get("proposalId"),new WritingCommands.ProposalDecision("yes","APPROVE"));
  AtomicBoolean failNinth=new AtomicBoolean(true);
  when(chapterModel.run(any(),any(),any(),anyInt(),anyString(),anyString(),anyList(),anyList(),anyString(),isNull(),any(),any())).thenAnswer(a->{
   int n=a.getArgument(3);if(n==9&&failNinth.get())throw new WritingRuleException(503,"MODEL_UNAVAILABLE");
   var outcome=new ChapterOutcome();outcome.bodyMarkdown="固定测试正文第"+n+"章";
   return new ChapterAgentRuntime.AgentRunResult(outcome,0,List.of(),"",1,"");
  });
  when(mainModel.run(any(),any(),anyString(),anyList(),anyString(),any())).thenAnswer(a->{
   WorkspaceMainAgent.Tools tools=a.getArgument(5);tools.invoke("read_writing_project","read",Map.of());
   try{tools.invoke("draft_confirmed_chapters","batch",Map.of("instruction","测试全部章节"));}catch(WritingRuleException ignored){}
   return "本轮执行结束，请核对真实进度";
  });
  var root=stages.submitConversation(conv,new StageAiRuns.ConversationTurn("first","写全部章节"));
  UUID id=UUID.fromString(root.get("rootRunId").toString());waitClosed(id);
  assertEquals("FAILED",stages.read(id).get("status"));
  var children=journal.tree(id).stream().filter(r->!r.runId().equals(id)).toList();assertEquals(1,children.size());
  assertNotNull(runs.get(children.get(0).runId()).receipt(),"子任务失败也必须有固定回执");
  var before=chapterSnapshots(p);assertEquals(8,before.size());
  failNinth.set(false);
  var next=stages.submitConversation(conv,new StageAiRuns.ConversationTurn("continue","继续剩余章节"));
  UUID nextId=UUID.fromString(next.get("rootRunId").toString());waitClosed(nextId);
  assertEquals("SUCCEEDED",stages.read(nextId).get("status"));
  var after=chapterSnapshots(p);assertEquals(20,after.size());before.forEach((k,v)->assertEquals(v,after.get(k),"已有章不能重生成"));
 }
 private Map<String,String> chapterSnapshots(UUID p){Map<String,String> result=new LinkedHashMap<>();var article=(Map<?,?>)writing.workbench(p).get("article");for(Object value:(List<?>)article.get("chapters")){var c=(Map<?,?>)value;if(c.get("chapterRevisionId")!=null)result.put(c.get("chapterKey").toString(),c.get("chapterRevisionId")+"|"+c.get("bodyMarkdown"));}return result;}
 private void waitClosed(UUID run)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);while(System.nanoTime()<end){if(!Set.of("QUEUED","RUNNING").contains(stages.read(run).get("status")))return;Thread.sleep(30);}fail("任务未收口");}
}
