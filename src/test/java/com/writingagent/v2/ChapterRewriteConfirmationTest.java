package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.*;
import static com.writingagent.v2.domain.DocumentJsonSupport.*;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.capability.*;
import com.writingagent.v2.domain.WritingRuleException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Public writing seam: a generated candidate is not a saved chapter. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class ChapterRewriteConfirmationTest {
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",com.writingagent.v2.support.TestDatabase::url);r.add("spring.flyway.clean-disabled",()->true);}
 @Autowired ProjectApplicationService projects;
 @Autowired WritingCapability writing;
 @Autowired WorkspaceConversations conversations;
 @Autowired com.writingagent.v2.application.port.AiRunPort runs;
 @Autowired com.writingagent.v2.runtime.RunJournal journal;
 @Autowired org.springframework.transaction.support.TransactionTemplate tx;
 private record Fixture(UUID project,UUID chapter,UUID other){}
 private Fixture fixture(){
  UUID ws=(UUID)projects.createWorkspace("Rewrite HITL isolated","local-fixture").get("workspaceId"),p=(UUID)projects.createProject(ws,"改章确认测试","两章").get("projectId");
  var saved=writing.savePlan(p,new WritingCommands.PlanSave("plan",1L,null,parse("{\"chapters\":[{\"title\":\"目标章\"},{\"title\":\"其它章\"}]}")));
  var card=writing.prepare(p,new WritingCommands.ProposalPrepare("plan-card","CONFIRM_PLAN","HTTP",(UUID)saved.get("planRevisionId"),2L,null));writing.decide((UUID)card.get("proposalId"),new WritingCommands.ProposalDecision("confirm","APPROVE"));
  var a=writing.article(p);var chapters=(List<Map<String,Object>>)a.get("chapters");UUID first=(UUID)chapters.get(0).get("chapterKey"),other=(UUID)chapters.get(1).get("chapterKey");
  for(UUID key:List.of(first,other)){a=writing.article(p);writing.saveChapter(p,key,new WritingCommands.ChapterSave("seed-"+key,(UUID)a.get("planRevisionId"),null,((Number)a.get("articleVersion")).longValue(),doc("原正文")));}
  return new Fixture(p,first,other);
 }
 private static com.fasterxml.jackson.databind.JsonNode doc(String text){var d=JSON.createObjectNode().put("type","doc");d.putArray("content").addObject().put("type","paragraph").putArray("content").addObject().put("type","text").put("text",text);return d;}
 private Map<String,Object> candidate(Fixture f,String key){var state=writing.workbench(f.project());var a=(Map<String,Object>)state.get("article");var ch=writing.chapter(f.project(),f.chapter());return writing.prepareChapterCandidate(f.project(),key,f.chapter(),(UUID)a.get("planRevisionId"),(UUID)ch.get("chapterRevisionId"),((Number)a.get("articleVersion")).longValue(),doc("新正文 xxs"),"约100字，结尾xxs",null,null);}
 @Test void candidateDoesNotChangeTheChapterUntilApprovedAndReplayCannotWriteTwice(){
  var f=fixture();var before=writing.chapter(f.project(),f.chapter());var other=writing.chapter(f.project(),f.other());var card=candidate(f,"candidate");
  assertEquals("CONFIRM_CHAPTER",card.get("type"));assertEquals(before,writing.chapter(f.project(),f.chapter()));
  var result=writing.decide((UUID)card.get("proposalId"),new WritingCommands.ProposalDecision("apply","APPROVE"));
  assertEquals("CHAPTER_SAVED",result.get("outcomeCode"));assertTrue(writing.chapter(f.project(),f.chapter()).get("bodyMarkdown").toString().endsWith("xxs"));assertEquals(other,writing.chapter(f.project(),f.other()));
  var replay=writing.decide((UUID)card.get("proposalId"),new WritingCommands.ProposalDecision("apply","APPROVE"));assertEquals(result.get("chapterRevisionId").toString(),replay.get("chapterRevisionId").toString());assertEquals(2L,writing.chapter(f.project(),f.chapter()).get("revisionNo"));
 }
 @Test void rejectingTheCandidateKeepsBothChaptersUnchanged(){var f=fixture();var before=writing.article(f.project());var card=candidate(f,"reject-candidate");writing.decide((UUID)card.get("proposalId"),new WritingCommands.ProposalDecision("reject","REJECT"));assertEquals(before,writing.article(f.project()));assertEquals("REJECTED",writing.proposal((UUID)card.get("proposalId")).get("status"));}
 @Test void anEditInvalidatesTheCandidateAndCannotBeOverwrittenByAnOldApproval(){
  var f=fixture();var card=candidate(f,"stale-candidate");var a=writing.article(f.project());var ch=writing.chapter(f.project(),f.chapter());
  writing.saveChapter(f.project(),f.chapter(),new WritingCommands.ChapterSave("manual-edit",(UUID)a.get("planRevisionId"),(UUID)ch.get("chapterRevisionId"),((Number)a.get("articleVersion")).longValue(),doc("用户的新正文")));
  assertEquals("STALE",writing.proposal((UUID)card.get("proposalId")).get("status"));assertThrows(WritingRuleException.class,()->writing.decide((UUID)card.get("proposalId"),new WritingCommands.ProposalDecision("old","APPROVE")));assertEquals("用户的新正文",writing.chapter(f.project(),f.chapter()).get("bodyMarkdown"));
 }
 @Test void completedArticleCannotAcceptACandidateUntilTheReopenCardIsApproved(){
  var f=fixture();var state=writing.workbench(f.project());var p=(Map<String,Object>)state.get("project");var a=(Map<String,Object>)state.get("article");
  var complete=writing.prepare(f.project(),new WritingCommands.ProposalPrepare("complete","COMPLETE_ARTICLE","HTTP",null,((Number)p.get("projectVersion")).longValue(),((Number)a.get("articleVersion")).longValue()));writing.decide((UUID)complete.get("proposalId"),new WritingCommands.ProposalDecision("finish","APPROVE"));
  assertThrows(WritingRuleException.class,()->candidate(f,"before-reopen"));
  a=writing.article(f.project());var reopen=writing.prepare(f.project(),new WritingCommands.ProposalPrepare("reopen","REOPEN_ARTICLE","HTTP",null,((Number)p.get("projectVersion")).longValue(),((Number)a.get("articleVersion")).longValue()));
  assertEquals("COMPLETE",writing.article(f.project()).get("status"));writing.decide((UUID)reopen.get("proposalId"),new WritingCommands.ProposalDecision("open","APPROVE"));
  var card=candidate(f,"after-reopen");assertEquals("OPEN",card.get("status"));assertEquals("原正文",writing.chapter(f.project(),f.chapter()).get("bodyMarkdown"));
 }
 @Test void reopenCardDurablyBindsTheOriginalChapterAndInstructionAndRejectionDoesNotResume(){
  var f=fixture();var state=writing.workbench(f.project());var p=(Map<String,Object>)state.get("project");var a=(Map<String,Object>)state.get("article");
  var complete=writing.prepare(f.project(),new WritingCommands.ProposalPrepare("complete","COMPLETE_ARTICLE","HTTP",null,((Number)p.get("projectVersion")).longValue(),((Number)a.get("articleVersion")).longValue()));writing.decide((UUID)complete.get("proposalId"),new WritingCommands.ProposalDecision("finish","APPROVE"));
  UUID ws=(UUID)p.get("workspaceId"),conv=(UUID)conversations.create(ws).get("conversationId");conversations.select(conv,f.project());
  var root=tx.execute(s->runs.register(ws,f.project(),conv,"rewrite-turn","digest",JSON.createObjectNode().put("operation","ASSISTANT_TURN")));
  var card=writing.prepareRewriteReopen(f.project(),"reopen-bound",f.chapter(),"约100字，结尾xxs",conv,root.runId());
  var recovered=writing.proposal((UUID)card.get("proposalId"));var intent=JSON.valueToTree(recovered.get("continuation"));assertEquals(f.chapter().toString(),intent.path("chapterKey").asText());assertEquals("约100字，结尾xxs",intent.path("instruction").asText());assertEquals(conv,recovered.get("conversationId"));
  assertEquals("COMPLETE",writing.article(f.project()).get("status"));writing.decide((UUID)card.get("proposalId"),new WritingCommands.ProposalDecision("no-reopen","REJECT"));assertFalse(writing.approvedRewriteContinuations().contains(card.get("proposalId")));assertEquals("COMPLETE",writing.article(f.project()).get("status"));
  tx.executeWithoutResult(s->{journal.settle(root.runId(),com.writingagent.v2.domain.RunStatus.SUCCEEDED,0,"TEST",false);runs.finish(root,JSON.createObjectNode(),true,false);});
 }
}
