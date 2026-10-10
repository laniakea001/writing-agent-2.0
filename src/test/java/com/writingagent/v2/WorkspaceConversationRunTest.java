package com.writingagent.v2;
import static org.junit.jupiter.api.Assertions.*;
import static com.writingagent.v2.domain.DocumentJsonSupport.*;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.capability.*;
import com.writingagent.v2.application.port.AiRunPort;
import com.writingagent.v2.domain.*;
import com.writingagent.v2.runtime.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
@SpringBootTest
@EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class WorkspaceConversationRunTest {
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",com.writingagent.v2.support.TestDatabase::url);}
 @Autowired ProjectApplicationService projects;@Autowired WorkspaceConversations conversations;@Autowired AiRunPort runs;@Autowired TransactionTemplate tx;@Autowired RunJournal journal;@Autowired RunReplay replay;@Autowired WritingCapability writing;
 @Test void projectlessRootHasDurableScopeAdmissionAndReplay(){
  UUID ws=(UUID)projects.createWorkspace("无文章运行测试","local-fixture").get("workspaceId");UUID conv=(UUID)conversations.create(ws).get("conversationId");
  var input=JSON.createObjectNode().put("operation","ASSISTANT_TURN").put("message","普通问题");
  var root=tx.execute(s->runs.register(ws,null,conv,"same","digest",input));assertNull(journal.scope(root.runId()).projectId());
  assertEquals(root.runId(),tx.execute(s->runs.register(ws,null,conv,"same","digest",input)).runId());
  assertEquals("CONVERSATION_BUSY",assertThrows(WritingRuleException.class,()->tx.execute(s->runs.register(ws,null,conv,"other","digest",input))).code());
  assertEquals("CONVERSATION_BUSY",assertThrows(WritingRuleException.class,()->conversations.select(conv,null)).code());
  journal.append(root.runId(),new RunJournal.Draft("test","STATUS","STATUS",null,null,true,Map.of("message","测试事件")));
  tx.executeWithoutResult(s->{journal.settle(root.runId(),RunStatus.SUCCEEDED,0,"TEST",false);runs.finish(root,JSON.valueToTree(Map.of("outcomeCode","TEST")),true,false);});
  assertTrue(replay.next(null,conv,root.runId(),null).complete());assertEquals(2,replay.next(null,conv,root.runId(),null).deliveries().size());
  assertDoesNotThrow(()->conversations.select(conv,null));
 }
 @Test void sealedUnknownCanBeReconciledWithoutReexecutingOrChangingItsReceipt(){
  UUID ws=(UUID)projects.createWorkspace("未知恢复测试","local-fixture").get("workspaceId");UUID conv=(UUID)conversations.create(ws).get("conversationId");
  var root=tx.execute(s->runs.register(ws,null,conv,"unknown","digest",JSON.createObjectNode().put("operation","ASSISTANT_TURN")));
  assertEquals("RUN_STILL_ACTIVE",assertThrows(WritingRuleException.class,()->stages.reconcile(root.runId(),"too-early")).code());
  var old=JSON.valueToTree(Map.of("errorCode","ORIGINAL_UNKNOWN","resultMayExist",true));
  tx.executeWithoutResult(s->{journal.settle(root.runId(),RunStatus.UNKNOWN,0,"ORIGINAL_UNKNOWN",true);runs.finish(root,old,false,true);});
  var recovered=stages.reconcile(root.runId(),"recover");assertEquals("RECONCILED_SAVED_RESULTS",recovered.get("outcomeCode"));
  assertEquals(recovered,stages.reconcile(root.runId(),"recover"));assertEquals(old,runs.get(root.runId()).receipt());
  assertEquals("RUN_ALREADY_TERMINAL",assertThrows(RunContractException.class,()->tx.executeWithoutResult(s->journal.guardBusinessCommit(root.runId()))).code());
  tx.executeWithoutResult(s->runs.finish(root,JSON.valueToTree(Map.of("outcomeCode","LATE_CALLBACK")),true,false));
  assertEquals(old,runs.get(root.runId()).receipt(),"迟到回调不能覆盖历史回执");
  assertEquals(RunStatus.UNKNOWN,journal.get(root.runId()).status());assertDoesNotThrow(()->conversations.select(conv,null));
  var fresh=tx.execute(s->runs.register(ws,null,conv,"new","digest",JSON.createObjectNode().put("operation","ASSISTANT_TURN")));
  assertDoesNotThrow(()->stages.reconcile(root.runId(),"recover"));
  assertEquals("CONVERSATION_BUSY",assertThrows(WritingRuleException.class,()->conversations.select(conv,null)).code());
  tx.executeWithoutResult(s->{journal.settle(fresh.runId(),RunStatus.SUCCEEDED,0,"TEST",false);runs.finish(fresh,JSON.createObjectNode(),true,false);});
 }
 @Autowired StageAiRuns stages;
 @Test void rootAndRealToolChildPreserveIdentityAndPayloadDigest(){
  UUID ws=(UUID)projects.createWorkspace("工具子运行测试","local-fixture").get("workspaceId");UUID p=(UUID)projects.createProject(ws,"测试文章","测试").get("projectId");UUID conv=(UUID)conversations.create(ws).get("conversationId");
  var root=tx.execute(s->runs.register(ws,null,conv,"main","digest",JSON.createObjectNode().put("operation","ASSISTANT_TURN")));
  var request=JSON.createObjectNode().put("operation","GENERATE_PLAN");var child=tx.execute(s->runs.registerChild(root,p,"real-call-test","propose_writing_plan",request));
  assertEquals(root.runId(),journal.get(child.runId()).parentRunId());assertEquals("real-call-test",journal.get(child.runId()).parentToolCallId());
  assertEquals(child.runId(),tx.execute(s->runs.registerChild(root,p,"real-call-test","propose_writing_plan",request)).runId());
  assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(WritingRuleException.class,()->tx.execute(s->runs.registerChild(root,p,"real-call-test","propose_writing_plan",JSON.createObjectNode().put("operation","OTHER")))).code());
  tx.executeWithoutResult(s->{journal.settle(child.runId(),RunStatus.SUCCEEDED,0,"TEST",false);runs.finish(child,JSON.createObjectNode(),true,false);journal.settle(root.runId(),RunStatus.SUCCEEDED,0,"TEST",false);runs.finish(root,JSON.createObjectNode(),true,false);});
  assertEquals(2,journal.get(root.runId()).terminalManifest().size());assertTrue(replay.next(null,conv,root.runId(),null).complete());
 }
 @Test void agentProposalRemainsOpenAndHttpCannotForgeOrigin()throws Exception{
  UUID ws=(UUID)projects.createWorkspace("确认规则测试","local-fixture").get("workspaceId");UUID p=(UUID)projects.createProject(ws,"确认测试","两章").get("projectId");
  var receipt=writing.savePlan(p,new WritingCommands.PlanSave("plan",1L,null,parse("{\"chapters\":[{\"title\":\"第一章\"}]}")));var current=writing.project(p);UUID plan=UUID.fromString(receipt.get("planRevisionId").toString());
  var command=new WritingCommands.ProposalPrepare("card","CONFIRM_PLAN","AGENT",plan,((Number)current.get("projectVersion")).longValue(),null);
  assertEquals("INVALID_REQUEST",assertThrows(WritingRuleException.class,()->writing.prepare(p,command)).code());
  var card=writing.prepareAgent(p,command);assertEquals("OPEN",card.get("status"));assertEquals("DRAFT",writing.plan(p,plan).get("status"));
  writing.savePlan(p,new WritingCommands.PlanSave("change",((Number)current.get("projectVersion")).longValue(),plan,parse("{\"chapters\":[{\"title\":\"更新大纲\"}]}")));
  assertEquals("STALE",writing.proposal((UUID)card.get("proposalId")).get("status"));
 }
}
