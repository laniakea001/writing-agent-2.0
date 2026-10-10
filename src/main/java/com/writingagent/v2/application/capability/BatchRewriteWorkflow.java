package com.writingagent.v2.application.capability;

import static com.writingagent.v2.domain.DocumentJsonSupport.*;
import com.writingagent.v2.application.port.BatchRewritePort;
import com.writingagent.v2.application.port.BatchRewritePort.*;
import com.writingagent.v2.application.port.WritingDocumentPort;
import com.writingagent.v2.application.support.RequestDigests;
import com.writingagent.v2.domain.WritingRuleException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared public batch entry. Submission is durable intent, never a chapter write. */
@Service
public class BatchRewriteWorkflow {
 public record Request(String clientActionId,List<UUID> chapterKeys,String instruction) {}
 public record Control(String clientActionId,long expectedBatchVersion,String action) {}
 private final BatchRewritePort batches;
 private final WritingDocumentPort documents;
 private final WritingCapability writing;
 private final TransactionTemplate tx;
 private final com.writingagent.v2.application.port.AiRunPort runs;
 private final com.writingagent.v2.runtime.RunJournal journal;
 public BatchRewriteWorkflow(BatchRewritePort batches,WritingDocumentPort documents,WritingCapability writing,TransactionTemplate tx,com.writingagent.v2.application.port.AiRunPort runs,com.writingagent.v2.runtime.RunJournal journal){this.batches=batches;this.documents=documents;this.writing=writing;this.tx=tx;this.runs=runs;this.journal=journal;}
 public Batch submit(UUID project,Request request){
  return submit(project,request,null,null);
 }
 public Batch submitForAgent(UUID project,Request request,UUID conversation,UUID sourceRun){
  var execution=runs.get(sourceRun);var p=writing.project(project);
  if(!Objects.equals(execution.conversationId(),conversation)||!Objects.equals(execution.workspaceId(),p.get("workspaceId")))throw error(403,"TARGET_SCOPE_REJECTED");
  return submit(project,request,conversation,sourceRun);
 }
 private Batch submit(UUID project,Request request,UUID conversation,UUID sourceRun){
  if(request==null||request.clientActionId()==null||request.clientActionId().isBlank()||request.clientActionId().length()>300||request.chapterKeys()==null||request.chapterKeys().isEmpty()||request.chapterKeys().stream().anyMatch(Objects::isNull)||request.instruction()==null||request.instruction().isBlank()||request.instruction().length()>12000)throw error(400,"INVALID_BATCH_REQUEST");
  writing.project(project);var keys=new HashSet<>(request.chapterKeys());
  String digest=RequestDigests.sha256(project+"|"+canonical(JSON.valueToTree(keys.stream().map(UUID::toString).sorted().toList()))+"|"+request.instruction());
  return tx.execute(s->{
   var p=documents.project(project,true);var prior=batches.lookup(project,request.clientActionId());
   if(prior.isPresent()){if(!digest.equals(prior.get().digest()))throw error(409,"IDEMPOTENCY_KEY_REUSED");return prior.get();}
   if(batches.hasActive(project))throw error(409,"BATCH_WRITE_BUSY");
   var a=documents.article(project,true).orElseThrow(()->error(409,"PLAN_NOT_CONFIRMED"));
   if(!documents.plan(a.planId()).map(plan->"CONFIRMED".equals(plan.status())).orElse(false)||!a.planId().equals(p.planId()))throw error(409,"PLAN_NOT_CONFIRMED");
   var chapters=documents.chapters(a.id());var selected=chapters.stream().filter(c->keys.contains(c.key())).sorted(Comparator.comparingInt(WritingDocumentPort.Chapter::index)).toList();
   if(selected.size()!=keys.size())throw error(404,"CHAPTER_NOT_FOUND");
   // Preserve the whole article snapshot: unrelated manual changes must not silently rebase this batch.
   var manifest=writing.rewriteManifest(project);
   var items=new ArrayList<Item>();int ordinal=0;for(var c:selected)items.add(new Item(UUID.randomUUID(),++ordinal,c.key(),c.index(),c.revisionId(),ItemStatus.PENDING,null,null));
   var inserted=batches.insert(p.workspaceId(),project,request.clientActionId(),digest,request.instruction(),"COMPLETE".equals(a.status())?Status.WAITING_REOPEN:Status.RUNNING,manifest,items);
   if(conversation!=null)batches.bindAgentContext(inserted.batchId(),conversation,sourceRun);
   if(inserted.status()==Status.WAITING_REOPEN){var card=writing.prepareBatchReopen(project,inserted.batchId(),JSON.valueToTree(items.stream().map(i->Map.of("chapterKey",i.chapterKey(),"chapterIndex",i.chapterIndex())).toList()),request.instruction(),conversation,sourceRun);batches.bindReopen(inserted.batchId(),(UUID)card.get("proposalId"));}
   return batches.get(inserted.batchId()).orElseThrow();
  });
 }
 public Batch read(UUID batchId){var batch=batches.get(batchId).orElseThrow(()->error(404,"BATCH_NOT_FOUND"));writing.project(batch.projectId());return batch;}
 public Optional<Batch> active(UUID projectId){writing.project(projectId);return batches.active(projectId);}
 public Batch lookup(UUID project,String clientActionId){writing.project(project);return batches.lookup(project,clientActionId).orElseThrow(()->error(404,"BATCH_NOT_FOUND"));}
 public com.fasterxml.jackson.databind.JsonNode control(UUID id,Control request){
  if(request==null||request.clientActionId()==null||request.clientActionId().isBlank()||request.clientActionId().length()>300||!Set.of("PAUSE","RESUME","STOP").contains(request.action()==null?"":request.action()))throw error(400,"INVALID_BATCH_CONTROL");
  var hint=read(id);String digest=RequestDigests.sha256(write(canonical(JSON.valueToTree(request))));
  return tx.execute(s->{documents.project(hint.projectId(),true);var prior=batches.controlReceipt(id,request.clientActionId(),digest);if(prior.isPresent())return prior.get();var batch=read(id);
   if(batch.version()!=request.expectedBatchVersion())throw error(409,"BATCH_VERSION_CONFLICT");
   if(Set.of(Status.STOPPED,Status.COMPLETED,Status.COMPLETED_WITH_SKIPS).contains(batch.status()))throw error(409,"BATCH_ALREADY_FINISHED");
   Status next;
   if("STOP".equals(request.action()))next=Status.STOPPED;
   else if("PAUSE".equals(request.action())){if(batch.status()==Status.UNKNOWN)throw error(409,"BATCH_UNKNOWN_REQUIRES_RECONCILIATION");next=Status.PAUSED;}
   else {
    if(batch.status()!=Status.PAUSED)throw error(409,"BATCH_NOT_PAUSED");
    if(!write(canonical(batch.manifest())).equals(write(canonical(writing.rewriteManifest(batch.projectId())))))throw error(409,"TARGET_CHANGED");
    var item=batch.items().stream().filter(i->!Set.of(ItemStatus.APPLIED,ItemStatus.REJECTED).contains(i.status())).findFirst().orElseThrow(()->error(409,"BATCH_ALREADY_FINISHED"));
    if(Set.of(ItemStatus.FAILED,ItemStatus.UNKNOWN).contains(item.status()))throw error(409,"BATCH_RETRY_NOT_ALLOWED");
    next=batch.reopenProposalId()!=null&&"OPEN".equals(writing.proposal(batch.reopenProposalId()).get("storedStatus"))?Status.WAITING_REOPEN:item.status()==ItemStatus.WAITING_CONFIRMATION?Status.WAITING_CHAPTER_CONFIRMATION:Status.RUNNING;
   }
   var receipt=JSON.createObjectNode().put("batchId",id.toString()).put("action",request.action()).put("status",next.name()).put("version",batch.version()+1).put("providerCancelled",false);
   batches.recordControl(id,request.clientActionId(),digest,next,receipt);return receipt;
  });
 }
 /** Trusted delivery entry opts in only the accepted batch, never historical fixtures. */
 public void activate(UUID batchId){var batch=read(batchId);tx.executeWithoutResult(s->{documents.project(batch.projectId(),true);batches.activate(batchId);});}
 public List<UUID> dispatchableIds(){return batches.dispatchableIds();}
 public List<GenerationOwner> activeGenerationOwners(){return batches.activeGenerationOwners();}
 /** Single local service contract: persisted owner PID must be confirmed stopped. */
 public Batch reconcileStopped(UUID id,long stoppedPid){
  var hint=read(id);return tx.execute(s->{documents.project(hint.projectId(),true);var owner=batches.generationOwner(id);
   if(owner.isEmpty())return read(id); // durable candidate/decision already bound; nothing to regenerate
   if(owner.get().pid()!=stoppedPid||stoppedPid<=0||ProcessHandle.of(stoppedPid).map(ProcessHandle::isAlive).orElse(false))throw error(409,"OWNER_NOT_CONFIRMED_STOPPED");
   var execution=runs.get(owner.get().runId());var run=journal.get(execution.runId());
   if(!run.terminal()){int models=0;long cursor=0;while(true){var events=journal.page(execution.runId(),cursor,256);models+=events.stream().filter(e->"MODEL_STARTED".equals(e.outputType())).count();if(events.size()<256)break;cursor=events.get(events.size()-1).sequence();}journal.settle(execution.runId(),com.writingagent.v2.domain.RunStatus.UNKNOWN,models,"BATCH_OWNER_STOPPED",true);}
   var receipt=JSON.createObjectNode().put("errorCode","BATCH_OWNER_STOPPED").put("resultMayExist",true).put("generated",false);
   runs.finish(execution,receipt,false,true);batches.generationFailed(id,execution.runId(),true);return read(id);
  });
 }
 public void requireAiWriteAvailable(UUID project){writing.project(project);if(batches.hasActive(project))throw error(409,"BATCH_WRITE_BUSY");}
 public void generationFailed(UUID batchId,UUID runId,boolean uncertain){var batch=read(batchId);tx.executeWithoutResult(s->{documents.project(batch.projectId(),true);batches.generationFailed(batchId,runId,uncertain);});}
 /** Claim + durable Run + execution snapshot commit together; no model is invoked here. */
 public Optional<com.writingagent.v2.application.port.AiRunPort.Execution> claimNextRun(UUID batchId){
  var hint=read(batchId);
  return tx.execute(s->{
   documents.project(hint.projectId(),true);documents.article(hint.projectId(),true).orElseThrow();var batch=read(batchId);
   if(batch.status()!=Status.RUNNING)return Optional.empty();
   var pending=batch.items().stream().filter(i->!Set.of(ItemStatus.APPLIED,ItemStatus.REJECTED).contains(i.status())).findFirst();
   if(pending.isEmpty()||pending.get().status()!=ItemStatus.PENDING)return Optional.empty();var item=pending.get();
   if(!write(canonical(batch.manifest())).equals(write(canonical(writing.rewriteManifest(batch.projectId()))))){batches.pauseChanged(batchId);return Optional.empty();}
   UUID conversation=batch.conversationId()==null?runs.conversation(batch.workspaceId(),batch.projectId()):batch.conversationId();
   var state=writing.workbench(batch.projectId());
   var input=JSON.createObjectNode().put("operation","BATCH_REWRITE_CANDIDATE").put("batchId",batchId.toString()).put("itemId",item.itemId().toString()).put("chapterKey",item.chapterKey().toString()).put("instruction",batch.instruction()+"\n本次仅处理第"+item.chapterIndex()+"章，按本章编号落实统一要求。其它章不变。");input.set("snapshot",JSON.valueToTree(state));
   String key="batch:"+batchId+":item:"+item.ordinal()+":generate";
   var execution=runs.register(batch.workspaceId(),batch.projectId(),conversation,key,RequestDigests.sha256(batchId+"|"+item.itemId()),input);
   if(!execution.winner())throw error(409,"BATCH_GENERATION_ALREADY_REGISTERED");
   batches.attachConversation(batchId,conversation);batches.claimGeneration(batchId,item.itemId(),execution.runId());
   return Optional.of(execution);
  });
 }
 /** Internal generated-result seam; never exposed as an HTTP write-body endpoint. */
 public Map<String,Object> prepareCandidate(UUID batchId,UUID itemId,com.fasterxml.jackson.databind.JsonNode content,UUID runId){
  var hint=read(batchId);
  return tx.execute(s->{
   documents.project(hint.projectId(),true);var a=documents.article(hint.projectId(),true).orElseThrow();var batch=read(batchId);
   var item=batch.items().stream().filter(i->!Set.of(ItemStatus.APPLIED,ItemStatus.REJECTED).contains(i.status())).findFirst().orElseThrow(()->error(409,"BATCH_ALREADY_FINISHED"));
   if(!item.itemId().equals(itemId)||!Set.of(Status.RUNNING,Status.PAUSED).contains(batch.status()))throw error(409,"BATCH_ITEM_CHANGED");
   if(item.status()==ItemStatus.GENERATING&&!Objects.equals(item.runId(),runId)||runId!=null&&!Objects.equals(item.runId(),runId))throw error(409,"BATCH_RESULT_RUN_MISMATCH");
   if(!write(canonical(batch.manifest())).equals(write(canonical(writing.rewriteManifest(batch.projectId())))))throw error(409,"TARGET_CHANGED");
   var chapter=documents.chapters(a.id()).stream().filter(c->c.key().equals(item.chapterKey())).findFirst().orElseThrow();
   var card=writing.prepareChapterCandidate(batch.projectId(),"batch-candidate:"+item.itemId(),chapter.key(),a.planId(),chapter.revisionId(),a.version(),content,batch.instruction(),batch.conversationId(),runId);
   batches.bindCandidate(batchId,itemId,(UUID)card.get("proposalId"),runId);return card;
  });
 }
 private static WritingRuleException error(int status,String code){return new WritingRuleException(status,code);}
}
