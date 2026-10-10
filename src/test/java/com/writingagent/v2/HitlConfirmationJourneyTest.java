package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.*;
import static com.writingagent.v2.domain.DocumentJsonSupport.*;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.capability.*;
import java.util.*;
import com.writingagent.v2.application.port.AiRunPort;
import com.writingagent.v2.runtime.RunJournal;
import com.writingagent.v2.domain.RunStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Public business interface against PG, new fixture per test, no model or cleanup. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class HitlConfirmationJourneyTest {
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",com.writingagent.v2.support.TestDatabase::url);
  r.add("spring.flyway.clean-disabled",()->true);
 }
 @Autowired ProjectApplicationService projects;
 @Autowired WritingCapability writing;
 @Autowired WorkspaceConversations conversations;
 @Autowired AiRunPort runs;
 @Autowired RunJournal journal;
 @Autowired TransactionTemplate tx;
 @Autowired org.springframework.jdbc.core.JdbcTemplate fixtureSql;
 private UUID project(){UUID ws=(UUID)projects.createWorkspace("HITL-A isolated","local-fixture").get("workspaceId");return (UUID)projects.createProject(ws,"HITL-A确认对象","两章测试").get("projectId");}
 private Map<String,Object> draftCard(UUID p){
  var plan=writing.savePlan(p,new WritingCommands.PlanSave("plan",1L,null,parse("{\"chapters\":[{\"title\":\"第一章\"},{\"title\":\"第二章\"}]}")));
  return writing.prepare(p,new WritingCommands.ProposalPrepare("card","CONFIRM_PLAN","HTTP",(UUID)plan.get("planRevisionId"),2L,null));
 }
 @Test void pendingCardExplainsTargetAndAllowedDecisionsWithoutConfirmingThePlan(){
  UUID p=project();var card=draftCard(p);
  assertEquals(List.of("APPROVE","REJECT"),card.get("allowedDecisions"));
  assertEquals("待你确认",card.get("statusLabel"));
  assertEquals("HITL-A确认对象",card.get("targetLabel"));
  assertNotNull(card.get("checkedAt"));
  assertEquals("DRAFT",((Map<?,?>)writing.workbench(p).get("plan")).get("status"));
 }
 @Test void aCardIsBoundToItsExactConversationAndTwoConversationsDoNotShareDecisions(){
  UUID p=project();var initial=draftCard(p);UUID ws=(UUID)initial.get("workspaceId");
  UUID c1=(UUID)conversations.create(ws).get("conversationId"),c2=(UUID)conversations.create(ws).get("conversationId");
  var request=JSON.createObjectNode().put("operation","ASSISTANT_TURN");
  var r1=tx.execute(s->runs.register(ws,null,c1,"r1","digest",request));
  var r2=tx.execute(s->runs.register(ws,null,c2,"r2","digest",request));
  UUID plan=(UUID)initial.get("targetPlanRevisionId");
  var first=writing.prepareAgent(p,new WritingCommands.ProposalPrepare("c1-card","CONFIRM_PLAN","AGENT",plan,2L,null),c1,r1.runId());
  var second=writing.prepareAgent(p,new WritingCommands.ProposalPrepare("c2-card","CONFIRM_PLAN","AGENT",plan,2L,null),c2,r2.runId());
  assertNotEquals(first.get("proposalId"),second.get("proposalId"));
  assertEquals(c1,first.get("conversationId"));
  var cards=(List<?>)conversations.confirmations(c1,0,10).get("items");
  assertEquals(1,cards.size());assertEquals(first.get("proposalId"),((Map<?,?>)cards.get(0)).get("proposalId"));
  writing.decide((UUID)first.get("proposalId"),new WritingCommands.ProposalDecision("reject-c1","REJECT"));
  assertEquals("OPEN",writing.proposal((UUID)second.get("proposalId")).get("status"));
  tx.executeWithoutResult(s->{journal.settle(r1.runId(),RunStatus.SUCCEEDED,0,"TEST",false);runs.finish(r1,JSON.createObjectNode(),true,false);journal.settle(r2.runId(),RunStatus.SUCCEEDED,0,"TEST",false);runs.finish(r2,JSON.createObjectNode(),true,false);});
  // Recovery must not depend on the last 100 runs in the workspace.
  for(int i=0;i<101;i++){final int index=i;tx.executeWithoutResult(s->{var next=runs.register(ws,null,c1,"later-"+index,"digest",request);journal.settle(next.runId(),RunStatus.SUCCEEDED,0,"TEST",false);runs.finish(next,JSON.createObjectNode(),true,false);});}
  var recovered=(List<?>)conversations.read(c1).get("confirmations");assertEquals(1,recovered.size());assertEquals(first.get("proposalId"),((Map<?,?>)recovered.get(0)).get("proposalId"));
 }
 @Test void approvalReplayReturnsTheFixedReceiptAndOldCardIsNoLongerWritable(){
  UUID p=project();var card=draftCard(p);UUID id=(UUID)card.get("proposalId");
  var first=writing.decide(id,new WritingCommands.ProposalDecision("once","APPROVE"));
  var replay=writing.decide(id,new WritingCommands.ProposalDecision("once","APPROVE"));
  assertEquals(first.get("actionId").toString(),replay.get("actionId").toString());
  assertEquals(first.get("projectVersion"),replay.get("projectVersion"));
  assertEquals("CONFIRMED",((Map<?,?>)writing.workbench(p).get("plan")).get("status"));
  var refreshed=writing.proposal(id);assertEquals("APPROVED",refreshed.get("status"));
  assertEquals(List.of(),refreshed.get("allowedDecisions"));assertNotNull(refreshed.get("receipt"));
  assertThrows(com.writingagent.v2.domain.WritingRuleException.class,()->writing.decide(id,new WritingCommands.ProposalDecision("opposite","REJECT")));
 }
 @Test void contentChangesInvalidateTheOldCardWithoutConfirmingNewContent(){
  UUID p=project();var card=draftCard(p);
  writing.savePlan(p,new WritingCommands.PlanSave("edit",2L,(UUID)card.get("targetPlanRevisionId"),parse("{\"chapters\":[{\"title\":\"新第一章\"},{\"title\":\"新第二章\"}]}")));
  var old=writing.proposal((UUID)card.get("proposalId"));assertEquals("STALE",old.get("status"));assertEquals(List.of(),old.get("allowedDecisions"));assertNotNull(old.get("disabledReasonText"));
  assertThrows(com.writingagent.v2.domain.WritingRuleException.class,()->writing.decide((UUID)card.get("proposalId"),new WritingCommands.ProposalDecision("stale","APPROVE")));
  assertEquals("DRAFT",((Map<?,?>)writing.workbench(p).get("plan")).get("status"));
 }
 @Test void expiredFixtureIsExplainedAndCannotCommit(){
  UUID p=project();var card=draftCard(p);UUID id=(UUID)card.get("proposalId");
  // Time-only fixture injection, exact new test proposal; never edits an existing user card.
  fixtureSql.update("UPDATE writing_v2.confirmation_proposal SET expires_at=now()-interval '1 second' WHERE proposal_id=?",id);
  var expired=writing.proposal(id);assertEquals("EXPIRED",expired.get("status"));assertEquals(List.of(),expired.get("allowedDecisions"));assertNotNull(expired.get("disabledReasonText"));
  assertThrows(com.writingagent.v2.domain.WritingRuleException.class,()->writing.decide(id,new WritingCommands.ProposalDecision("expired","APPROVE")));
  assertEquals("DRAFT",((Map<?,?>)writing.workbench(p).get("plan")).get("status"));
 }
}
