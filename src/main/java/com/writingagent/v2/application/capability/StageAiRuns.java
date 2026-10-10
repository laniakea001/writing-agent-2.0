package com.writingagent.v2.application.capability;

import static com.writingagent.v2.domain.DocumentJsonSupport.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.writingagent.v2.agent.plan.PlanAgentRuntime;
import com.writingagent.v2.agent.chapter.ChapterAgentRuntime;
import com.writingagent.v2.agent.chapter.ReadConfirmedOutlineTool;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.port.AiRunPort;
import com.writingagent.v2.application.support.RequestDigests;
import com.writingagent.v2.domain.PlanOutcome;
import com.writingagent.v2.domain.RunStatus;
import com.writingagent.v2.domain.WritingRuleException;
import com.writingagent.v2.runtime.RunJournal;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Short acceptance, durable identity, one managed execution; no model under a database lock. */
@Service
public class StageAiRuns {
    public record Request(String clientActionId,String operation,String instruction,UUID chapterKey) {}
    private final WritingCapability writing;
    private final AiRunPort port;
    private final RunJournal journal;
    private final TransactionTemplate tx;
    private final PlanAgentRuntime planAgent;
    private final ChapterAgentRuntime chapterAgent;
    private final ProjectApplicationService projects;
    private final WorkspaceConversations conversations;
    private final com.writingagent.v2.application.port.ConversationPort messages;
    private final com.writingagent.v2.agent.main.WorkspaceMainAgent mainAgent;
    private final BatchRewriteWorkflow batches;
    private final String dispatchProject;
    private final UUID owner=UUID.randomUUID();
    private final Semaphore slots=new Semaphore(18);
    private final ScheduledExecutorService continuations=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"writing-confirmed-continuations");t.setDaemon(true);return t;});
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"writing-stage-ai");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public StageAiRuns(WritingCapability writing,AiRunPort port,RunJournal journal,TransactionTemplate tx,
                       PlanAgentRuntime planAgent,ChapterAgentRuntime chapterAgent,ProjectApplicationService projects,
                       WorkspaceConversations conversations,com.writingagent.v2.application.port.ConversationPort messages,
                       com.writingagent.v2.agent.main.WorkspaceMainAgent mainAgent,BatchRewriteWorkflow batches,
                       @org.springframework.beans.factory.annotation.Value("${writing.batch.dispatch-enabled:false}") boolean batchDispatchEnabled,
                       @org.springframework.beans.factory.annotation.Value("${writing.batch.dispatch-project-id:}") String dispatchProject){
        this.writing=writing;this.port=port;this.journal=journal;this.tx=tx;this.planAgent=planAgent;this.chapterAgent=chapterAgent;this.projects=projects;
        this.conversations=conversations;this.messages=messages;this.mainAgent=mainAgent;
        this.batches=batches;
        this.dispatchProject=dispatchProject;
        continuations.scheduleWithFixedDelay(this::resumeApprovedRewrites,3,3,TimeUnit.SECONDS);
        if(batchDispatchEnabled)continuations.scheduleWithFixedDelay(this::dispatchBatches,3,3,TimeUnit.SECONDS);
    }
    public com.writingagent.v2.application.port.BatchRewritePort.Batch submitBatch(UUID project,BatchRewriteWorkflow.Request request){
        var batch=batches.submit(project,request);batches.activate(batch.batchId());return batches.read(batch.batchId());
    }
    /** Only explicit activated intents; an already claimed item is never executed again. */
    public void dispatchBatches(){
        try{for(var interrupted:batches.activeGenerationOwners())if(batchInScope(interrupted.batchId())&&!ProcessHandle.of(interrupted.pid()).map(ProcessHandle::isAlive).orElse(false))batches.reconcileStopped(interrupted.batchId(),interrupted.pid());
        for(UUID id:batches.dispatchableIds()){
            if(!batchInScope(id))continue;
            if(!slots.tryAcquire())return;
            AiRunPort.Execution e;
            try{var claimed=batches.claimNextRun(id);if(claimed.isEmpty()){slots.release();continue;}e=claimed.get();}
            catch(RuntimeException ex){slots.release();org.slf4j.LoggerFactory.getLogger(StageAiRuns.class).warn("Batch claim {} blocked: {}",id,ex instanceof WritingRuleException r?r.code():ex.getClass().getSimpleName());continue;}
            try{workers.execute(()->{try{execute(e.runId());}finally{slots.release();}});}
            catch(RejectedExecutionException ex){slots.release();fail(e,"EXECUTION_CAPACITY_FULL",false);}
        }}catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(StageAiRuns.class).warn("Batch dispatch unavailable: {}",ex.getClass().getSimpleName());}
    }
    private boolean batchInScope(UUID id){return dispatchProject.isBlank()||dispatchProject.equals(batches.read(id).projectId().toString());}
    public Map<String,Object> submit(UUID project,Request request){
        if(request.clientActionId()==null||request.clientActionId().isBlank()||request.clientActionId().length()>300||request.instruction()!=null&&request.instruction().length()>12000)throw new WritingRuleException(400,"INVALID_REQUEST");
        if(!Set.of("GENERATE_PLAN","GENERATE_CHAPTER","REWRITE_CHAPTER","DRAFT_MISSING_CHAPTERS").contains(request.operation()==null?"":request.operation()))throw new WritingRuleException(400,"OPERATION_UNSUPPORTED");
        var state=writing.workbench(project);var p=map(state.get("project"));UUID ws=uuid(p.get("workspaceId"));
        var input=JSON.valueToTree(request);
        String digest=RequestDigests.sha256(project+"|"+canonical(input));
        try {var prior=port.lookup(project,request.clientActionId());if(!prior.digest().equals(digest))throw new WritingRuleException(409,"IDEMPOTENCY_KEY_REUSED");return view(prior);}
        catch(WritingRuleException ex){if(!"ACTION_NOT_FOUND".equals(ex.code()))throw ex;}
        batches.requireAiWriteAvailable(project);
        if(!"GENERATE_PLAN".equals(request.operation())){
            var plan=map(state.get("plan"));if(!"CONFIRMED".equals(plan.get("status")))throw new WritingRuleException(409,"PLAN_NOT_CONFIRMED");
            var article=map(state.get("article"));if("COMPLETE".equals(article.get("status")))throw new WritingRuleException(409,"ARTICLE_COMPLETE");
            if(!"DRAFT_MISSING_CHAPTERS".equals(request.operation())){
                if(request.chapterKey()==null||list(article.get("chapters")).stream().noneMatch(c->request.chapterKey().toString().equals(String.valueOf(c.get("chapterKey")))))throw new WritingRuleException(404,"CHAPTER_NOT_FOUND");
                if("REWRITE_CHAPTER".equals(request.operation())&&(request.instruction()==null||request.instruction().isBlank()))throw new WritingRuleException(400,"REWRITE_INSTRUCTION_REQUIRED");
            }
        }
        UUID conv=port.conversation(ws,project);
        // Digest only the command. A replay must not acquire a new target/version snapshot.
        if(!slots.tryAcquire())throw new WritingRuleException(503,"EXECUTION_CAPACITY_FULL");
        AiRunPort.Execution e;
        try {e=tx.execute(s->{var snapshot=input.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)snapshot).set("snapshot",JSON.valueToTree(state));return port.register(ws,project,conv,request.clientActionId(),digest,snapshot);});}
        catch(RuntimeException ex){slots.release();throw ex;}
        if(!e.winner()){slots.release();return view(e);}
        try {workers.execute(()->{try{execute(e.runId());}finally{slots.release();}});}
        catch(RejectedExecutionException ex){slots.release();fail(e,"EXECUTION_CAPACITY_FULL",false);}
        return view(e);
    }
    public record ConversationTurn(String clientActionId,String message) {}
    public Map<String,Object> submitConversation(UUID conv,ConversationTurn request){
        var c=conversations.require(conv);
        if(request.clientActionId()==null||request.clientActionId().isBlank()||request.clientActionId().length()>300||request.message()==null||request.message().isBlank()||request.message().length()>12000)throw new WritingRuleException(400,"INVALID_REQUEST");
        String digest=RequestDigests.sha256(conv+"|"+canonical(JSON.valueToTree(request)));
        try{var prior=port.lookupConversation(conv,request.clientActionId());if(!digest.equals(prior.digest()))throw new WritingRuleException(409,"IDEMPOTENCY_KEY_REUSED");return view(prior);}catch(WritingRuleException ex){if(!"ACTION_NOT_FOUND".equals(ex.code()))throw ex;}
        if(!slots.tryAcquire())throw new WritingRuleException(503,"EXECUTION_CAPACITY_FULL");
        AiRunPort.Execution e;
        try{e=tx.execute(s->{var input=JSON.valueToTree(request);((com.fasterxml.jackson.databind.node.ObjectNode)input).put("operation","ASSISTANT_TURN");var accepted=port.register(c.workspaceId(),c.projectId(),conv,request.clientActionId(),digest,input);if(accepted.winner())messages.insertUserMessage(UUID.randomUUID(),conv,request.message());return accepted;});}catch(RuntimeException ex){slots.release();throw ex;}
        if(!e.winner()){slots.release();return view(e);}
        try{workers.execute(()->{try{execute(e.runId());}finally{slots.release();}});}catch(RejectedExecutionException ex){slots.release();fail(e,"EXECUTION_CAPACITY_FULL",false);}
        return view(e);
    }
    public Map<String,Object> lookupConversation(UUID conv,String key){conversations.require(conv);return view(port.lookupConversation(conv,key));}
    public Map<String,Object> read(UUID run){var e=port.get(run);writing.requireWorkspace(e.workspaceId());if(e.projectId()!=null)writing.project(e.projectId());return view(e);}
    public Map<String,Object> reconcile(UUID run,String clientActionId){
        if(clientActionId==null||clientActionId.isBlank()||clientActionId.length()>300)throw new WritingRuleException(400,"INVALID_REQUEST");
        var e=port.get(run);writing.requireWorkspace(e.workspaceId());if(e.projectId()!=null)writing.project(e.projectId());
        return tx.execute(s->{
            var snapshot=new LinkedHashMap<String,Object>();snapshot.put("outcomeCode","RECONCILED_SAVED_RESULTS");snapshot.put("rootRunId",run);snapshot.put("conversationId",e.conversationId());snapshot.put("projectId",e.projectId());snapshot.put("originalStatus",journal.get(run).status().name());snapshot.put("generated",false);
            if(e.projectId()!=null){var state=writing.workbench(e.projectId());var article=state.get("article");if(article!=null){var chapters=list(map(article).get("chapters"));snapshot.put("savedChapterIndexes",chapters.stream().filter(c->c.get("chapterRevisionId")!=null).map(c->c.get("chapterIndex")).toList());snapshot.put("remainingChapterIndexes",chapters.stream().filter(c->c.get("chapterRevisionId")==null).map(c->c.get("chapterIndex")).toList());}}
            return JSON.convertValue(port.reconcileTerminal(e,clientActionId,JSON.valueToTree(snapshot)),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        });
    }
    public Map<String,Object> lookup(UUID project,String key){writing.project(project);return view(port.lookup(project,key));}
    /** Durable approval is the authorization. Claim a single run, never regenerate on replay. */
    public void resumeApprovedRewrites(){
        try{for(UUID id:writing.approvedRewriteContinuations())try{resumeApprovedRewrite(id);}catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(StageAiRuns.class).warn("Confirmed rewrite continuation {} pending: {}",id,ex instanceof WritingRuleException r?r.code():ex.getClass().getSimpleName());}}catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(StageAiRuns.class).warn("Confirmed rewrite scan unavailable: {}",ex.getClass().getSimpleName());}
    }
    public Map<String,Object> resumeApprovedRewrite(UUID proposalId){
        var card=writing.proposal(proposalId);if(!"APPROVED".equals(card.get("status"))||!"REOPEN_ARTICLE".equals(card.get("type"))||!card.containsKey("continuation"))throw new WritingRuleException(409,"REWRITE_NOT_AUTHORIZED");
        UUID project=uuid(card.get("projectId")),conv=uuid(card.get("conversationId")),ws=uuid(card.get("workspaceId"));String key="resume-rewrite:"+proposalId;
        try{return view(port.lookup(project,key));}catch(WritingRuleException ex){if(!"ACTION_NOT_FOUND".equals(ex.code()))throw ex;}
        var state=writing.workbench(project);
        if(!slots.tryAcquire())throw new WritingRuleException(503,"EXECUTION_CAPACITY_FULL");
        AiRunPort.Execution execution;
        try{execution=tx.execute(s->{
            var receipt=JSON.valueToTree(card.get("receipt"));
            var continuation=JSON.valueToTree(card.get("continuation"));var input=JSON.createObjectNode().put("operation","REWRITE_CANDIDATE").put("chapterKey",continuation.path("chapterKey").asText()).put("instruction",continuation.path("instruction").asText());
            input.set("snapshot",JSON.valueToTree(state));input.put("approvedArticleVersion",receipt.path("articleVersion").asLong(-1));
            return port.register(ws,project,conv,key,RequestDigests.sha256(proposalId.toString()),input);
        });}catch(RuntimeException ex){slots.release();throw ex;}
        if(!execution.winner()){slots.release();return view(execution);}
        try{workers.execute(()->{try{execute(execution.runId());}finally{slots.release();}});}catch(RejectedExecutionException ex){slots.release();fail(execution,"EXECUTION_CAPACITY_FULL",false);}
        return view(execution);
    }
    public List<Map<String,Object>> list(UUID ws){writing.requireWorkspace(ws);return port.list(ws).stream().map(this::view).toList();}
    private Map<String,Object> view(AiRunPort.Execution e){
        var run=journal.get(e.runId());var v=new LinkedHashMap<String,Object>();
        v.put("rootRunId",e.runId());v.put("projectId",e.projectId());v.put("conversationId",e.conversationId());v.put("clientActionId",e.clientActionId());v.put("operation",e.request().path("operation").asText());v.put("status",run.status().name());v.put("result",e.receipt());return v;
    }
    private void execute(UUID run){
        var e=port.get(run);tx.executeWithoutResult(s->port.running(run,owner));
        publish(run,"RUN_STARTED",Map.of("agentRole","StageAgent","operation",e.request().path("operation").asText()));
        try {
            if("ASSISTANT_TURN".equals(e.request().path("operation").asText())){executeAssistant(e);return;}
            var results=new ArrayList<Map<String,Object>>();
            if("GENERATE_PLAN".equals(e.request().path("operation").asText()))results.add(generatePlan(e));
            else if("DRAFT_MISSING_CHAPTERS".equals(e.request().path("operation").asText())) {
                var article=map(writing.article(e.projectId()));
                for(var ch:list(article.get("chapters")))if(ch.get("chapterRevisionId")==null||ch.get("bodyMarkdown")==null||ch.get("bodyMarkdown").toString().isBlank())results.add(generateChapter(e,uuid(ch.get("chapterKey")),false));
            } else results.add(generateChapter(e,uuid(e.request().path("chapterKey").asText()),Set.of("REWRITE_CHAPTER","REWRITE_CANDIDATE","BATCH_REWRITE_CANDIDATE").contains(e.request().path("operation").asText())));
            var receipt=JSON.valueToTree(Map.of("outcomeCode","STAGE_COMPLETED","artifacts",results,"resultMayExist",!results.isEmpty()));
            tx.executeWithoutResult(s->{journal.settle(run,RunStatus.SUCCEEDED,modelCount(run),"STAGE_COMPLETED",!results.isEmpty());port.finish(e,receipt,true,false);});
        }catch(Exception ex){
            // A child is not a root: tree(child) throws ROOT_RUN_REQUIRED and used to
            // prevent the failure receipt from ever being recorded.
            String code=ex instanceof WritingRuleException r?r.code():ex instanceof com.writingagent.v2.agent.plan.StructuredOutputMissingException?"STRUCTURED_OUTPUT_MISSING":ex instanceof com.writingagent.v2.agent.plan.ModelUnavailableException?"MODEL_UNAVAILABLE":"EXECUTION_FAILED";
            boolean known=ex instanceof WritingRuleException&&!code.contains("UNKNOWN")||ex instanceof com.writingagent.v2.agent.plan.StructuredOutputMissingException||ex instanceof com.writingagent.v2.agent.plan.ModelUnavailableException;
            fail(e,code,!known,ex.getClass().getSimpleName());
        }
    }
    private boolean hasArtifact(UUID run){long after=0;while(true){var page=journal.page(run,after,256);if(page.stream().anyMatch(ev->Set.of("ARTIFACT_SAVED","CONFIRMATION_REQUIRED").contains(ev.outputType())))return true;if(page.size()<256)return false;after=page.get(page.size()-1).sequence();}}
    private void executeAssistant(AiRunPort.Execution e){
        var history=messages.listMessages(e.conversationId());
        var recent=history.subList(Math.max(0,history.size()-20),history.size()).stream().map(m->m.role()+": "+m.body().substring(0,Math.min(6000,m.body().length()))).toList();
        var session=new AssistantTools(e);
        var context=new LinkedHashMap<String,Object>();context.put("workspaceId",e.workspaceId());context.put("projectId",e.projectId());context.put("currentState",e.projectId()==null?null:writing.workbench(e.projectId()));
        String modelAnswer=mainAgent.run(e.runId(),e.conversationId(),write(context),recent,e.request().path("message").asText(),session::invoke);
        // Receipt-based guard, not keyword routing: model prose cannot substitute for a failed write or a pending card.
        String answer=session.pendingCard!=null?session.pendingCard:!session.failures.isEmpty()?"本次工具操作未完成（"+String.join("、",session.errorCodes)+"）。目标章节未由本次失败操作改写；不会用聊天正文替代保存。请按当前确认卡或错误提示处理。":modelAnswer;
        tx.executeWithoutResult(s->{journal.guardBusinessCommit(e.runId());UUID id=UUID.randomUUID();messages.insertAssistantMessage(id,e.conversationId(),answer,e.runId());publish(e.runId(),"ASSISTANT_FINAL",Map.of("messageId",id,"body",answer));boolean failed=!session.failures.isEmpty();boolean partial=failed&&journal.tree(e.runId()).stream().anyMatch(r->hasArtifact(r.runId()));String code=failed?"ASSISTANT_TOOL_FAILED":"ASSISTANT_COMPLETED";var receipt=JSON.valueToTree(Map.of("outcomeCode",code,"errorCode",failed?code:"","messageId",id,"body",answer,"resultMayExist",session.uncertain,"hasSavedArtifacts",partial));journal.settle(e.runId(),failed?(session.uncertain?RunStatus.UNKNOWN:RunStatus.FAILED):RunStatus.SUCCEEDED,modelCount(e.runId()),code,session.uncertain);port.finish(e,receipt,!failed,session.uncertain);if(!session.uncertain&&!Objects.equals(e.projectId(),session.target))port.selectProject(e.conversationId(),session.target);});
    }
    /** One trusted target and read snapshot per invocation; never model-supplied versions. */
    private class AssistantTools {
        private final AiRunPort.Execution root;private UUID target;private Map<String,Object> snapshot;
        private final Map<String,Object> completed=new HashMap<>();
        private final Set<String> failures=new HashSet<>();
        private final Set<String> errorCodes=new LinkedHashSet<>();
        private String pendingCard;
        private boolean generationFailed,uncertain;
        AssistantTools(AiRunPort.Execution root){this.root=root;this.target=root.projectId();}
        synchronized Object invoke(String name,String call,Map<String,Object> input){
            String callKey=call+"|"+name+"|"+canonical(JSON.valueToTree(input));if(completed.containsKey(callKey))return completed.get(callKey);
            try{Object result=perform(name,call,input);completed.put(callKey,result);failures.remove(name);return result;}
            catch(RuntimeException ex){failures.add(name);String code=ex instanceof WritingRuleException r?r.code():"TOOL_EXECUTION_FAILED";errorCodes.add(code);publish(root.runId(),"TOOL_BUSINESS_FAILED",Map.of("toolName",name,"toolCallId",call,"errorCode",code,"resultStatus","FAILED"));if(ex instanceof WritingRuleException r)throw r;throw new WritingRuleException(502,"TOOL_EXECUTION_FAILED");}
        }
        private Object perform(String name,String call,Map<String,Object> input){
            if(pendingCard!=null&&!name.startsWith("read_")&&!name.startsWith("list_"))return Map.of("outcomeCode","HUMAN_CONFIRMATION_REQUIRED","saved",false,"message",pendingCard);
            if(generationFailed&&!name.startsWith("read_")&&!name.startsWith("list_"))throw new WritingRuleException(409,"PREVIOUS_WRITE_FAILED");
            if(name.equals("list_writing_projects"))return writing.projects(root.workspaceId());
            if(name.equals("create_writing_project")){
                if(target!=null)throw new WritingRuleException(409,"TARGET_ALREADY_BOUND");
                String title=String.valueOf(input.getOrDefault("title","")),goal=String.valueOf(input.getOrDefault("researchGoal",""));
                if(title.isBlank()||title.length()>200||goal.isBlank()||goal.length()>12000)throw new WritingRuleException(400,"INVALID_REQUEST");
                var created=tx.execute(s->{journal.guardBusinessCommit(root.runId());var value=projects.createProject(root.workspaceId(),title,goal);publish(root.runId(),"ARTIFACT_SAVED",value);return value;});target=uuid(created.get("projectId"));snapshot=null;return created;
            }
            if(name.equals("select_writing_project")){
                UUID id=uuid(input.get("projectId"));if(target!=null&&!target.equals(id))throw new WritingRuleException(409,"TARGET_ALREADY_BOUND");
                if(!root.workspaceId().equals(writing.project(id).get("workspaceId")))throw new WritingRuleException(403,"TARGET_SCOPE_REJECTED");target=id;snapshot=null;return Map.of("projectId",id,"selected",true);
            }
            if(target==null)throw new WritingRuleException(409,"PROJECT_SELECTION_REQUIRED");
            if(name.equals("read_writing_project")||name.equals("read_writing_progress")){snapshot=writing.workbench(target);return snapshot;}
            if(name.equals("read_writing_chapter")){snapshot=writing.workbench(target);if(!(input.get("chapterIndex") instanceof Number n))throw new WritingRuleException(400,"INVALID_REQUEST");return list(map(snapshot.get("article")).get("chapters")).stream().filter(ch->((Number)ch.get("chapterIndex")).intValue()==n.intValue()).findFirst().orElseThrow(()->new WritingRuleException(404,"CHAPTER_NOT_FOUND"));}
            if(snapshot==null)throw new WritingRuleException(409,"PROJECT_READ_REQUIRED");
            String childKey="tool|"+root.runId()+"|"+call+"|"+name;
            if(!name.equals("rewrite_chapters"))batches.requireAiWriteAvailable(target);
            if(name.equals("rewrite_chapters")){
                if(!(input.get("chapterIndexes") instanceof List<?> indexes)||indexes.isEmpty()||!(input.get("instruction") instanceof String instruction)||instruction.isBlank())throw new WritingRuleException(400,"INVALID_BATCH_REQUEST");
                var chapters=list(map(snapshot.get("article")).get("chapters"));var keys=new ArrayList<UUID>();
                for(Object value:indexes){if(!(value instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<1)throw new WritingRuleException(400,"INVALID_BATCH_REQUEST");keys.add(chapters.stream().filter(ch->((Number)ch.get("chapterIndex")).intValue()==n.intValue()).map(ch->uuid(ch.get("chapterKey"))).findFirst().orElseThrow(()->new WritingRuleException(404,"CHAPTER_NOT_FOUND")));}
                var batch=tx.execute(s->{journal.guardBusinessCommit(root.runId());var accepted=batches.submitForAgent(target,new BatchRewriteWorkflow.Request(childKey,keys,instruction),root.conversationId(),root.runId());batches.activate(accepted.batchId());return accepted;});
                if(batch.reopenProposalId()!=null)publish(root.runId(),"CONFIRMATION_REQUIRED",writing.proposal(batch.reopenProposalId()));
                publish(root.runId(),"BATCH_ACCEPTED",Map.of("batchId",batch.batchId(),"chapterIndexes",batch.items().stream().map(i->i.chapterIndex()).toList(),"status",batch.status().name()));
                pendingCard=batch.reopenProposalId()!=null?"批量修改任务已登记。请先点击重新打开文章确认卡；之后逐章生成修改候选，由你逐章确认或拒绝。当前正文未改。":"批量修改任务已登记，将按目录顺序逐章生成候选。每一章都需要你确认或拒绝后才继续，当前正文未改。";
                return batch;
            }
            if(name.equals("update_article_metadata")){
                var article=map(snapshot.get("article"));var payload=JSON.valueToTree(input.get("payload"));
                var saved=tx.execute(s->{journal.guardBusinessCommit(root.runId());var result=writing.saveGeneratedMetadata(target,new WritingCommands.MetadataSave(childKey,((Number)article.get("articleVersion")).longValue(),nullableUuid(article.get("currentMetadataRevisionId")),payload));publish(root.runId(),"ARTIFACT_SAVED",result);return result;});snapshot=null;return saved;
            }
            if(name.equals("update_writing_plan")){
                var project=map(snapshot.get("project"));var payload=JSON.valueToTree(input.get("payload"));
                var saved=tx.execute(s->{journal.guardBusinessCommit(root.runId());var result=writing.saveGeneratedPlan(target,new WritingCommands.PlanSave(childKey,((Number)project.get("projectVersion")).longValue(),nullableUuid(project.get("currentPlanRevisionId")),payload));publish(root.runId(),"ARTIFACT_SAVED",result);return result;});snapshot=null;return saved;
            }
            if(name.startsWith("request_")){
                String type=switch(name){case "request_plan_confirmation"->"CONFIRM_PLAN";case "request_article_completion"->"COMPLETE_ARTICLE";case "request_article_reopen"->"REOPEN_ARTICLE";default->throw new WritingRuleException(400,"OPERATION_UNSUPPORTED");};
                var project=map(snapshot.get("project"));var article=snapshot.get("article")==null?null:map(snapshot.get("article"));
                Map<String,Object> card;
                if(name.equals("request_article_reopen")&&input.get("chapterIndex") instanceof Number n&&n.intValue()>0){
                    if(n.doubleValue()!=n.intValue()||!(input.get("instruction") instanceof String instruction)||instruction.isBlank())throw new WritingRuleException(400,"REWRITE_INSTRUCTION_REQUIRED");
                    UUID chapter=list(article.get("chapters")).stream().filter(ch->((Number)ch.get("chapterIndex")).intValue()==n.intValue()).map(ch->uuid(ch.get("chapterKey"))).findFirst().orElseThrow(()->new WritingRuleException(404,"CHAPTER_NOT_FOUND"));
                    card=tx.execute(s->{journal.guardBusinessCommit(root.runId());return writing.prepareRewriteReopen(target,childKey,chapter,instruction,root.conversationId(),root.runId());});
                }else card=tx.execute(s->{journal.guardBusinessCommit(root.runId());return writing.prepareAgent(target,new WritingCommands.ProposalPrepare(childKey,type,"AGENT",nullableUuid(project.get("currentPlanRevisionId")),((Number)project.get("projectVersion")).longValue(),article==null?null:((Number)article.get("articleVersion")).longValue()),root.conversationId(),root.runId());});
                publish(root.runId(),"CONFIRMATION_REQUIRED",card);
                pendingCard=card.containsKey("continuation")?"文章已完成，请点击「重新打开文章」确认卡。确认后继续生成第"+input.get("chapterIndex")+"章修改候选，再由你确认写入；当前正文未改。":"确认卡已生成，请点击卡片决定；聊天文字不能代替确认。";
                return card;
            }
            String op=switch(name){case "propose_writing_plan"->"GENERATE_PLAN";case "draft_confirmed_chapters"->"DRAFT_MISSING_CHAPTERS";case "draft_writing_chapter"->"GENERATE_CHAPTER";case "rewrite_target_chapter"->"REWRITE_CHAPTER";default->throw new WritingRuleException(400,"OPERATION_UNSUPPORTED");};
            var request=JSON.createObjectNode().put("operation",op).put("instruction",String.valueOf(input.getOrDefault("instruction","")));request.set("snapshot",JSON.valueToTree(snapshot));
            UUID chapter=null;
            if(op.equals("REWRITE_CHAPTER")||op.equals("GENERATE_CHAPTER")){if(!(input.get("chapterIndex") instanceof Number n)||n.doubleValue()!=n.intValue())throw new WritingRuleException(400,"INVALID_REQUEST");chapter=list(map(snapshot.get("article")).get("chapters")).stream().filter(ch->((Number)ch.get("chapterIndex")).intValue()==n.intValue()).map(ch->uuid(ch.get("chapterKey"))).findFirst().orElseThrow(()->new WritingRuleException(404,"CHAPTER_NOT_FOUND"));request.put("chapterKey",chapter.toString());if(op.equals("REWRITE_CHAPTER")&&request.path("instruction").asText().isBlank())throw new WritingRuleException(400,"REWRITE_INSTRUCTION_REQUIRED");}
            if(op.equals("REWRITE_CHAPTER")&&"COMPLETE".equals(map(snapshot.get("article")).get("status"))){
                UUID targetChapter=chapter;
                var card=tx.execute(s->{journal.guardBusinessCommit(root.runId());return writing.prepareRewriteReopen(target,childKey,targetChapter,request.path("instruction").asText(),root.conversationId(),root.runId());});publish(root.runId(),"CONFIRMATION_REQUIRED",card);
                pendingCard="文章已完成。请点击下方「重新打开文章」确认卡；确认后会继续生成第"+input.get("chapterIndex")+"章修改候选。候选还需你单独确认写入，当前正文未改动。";return card;
            }
            AiRunPort.Execution child=tx.execute(s->port.registerChild(root,target,call,name,request));
            if(!child.winner())return child.receipt()==null?Map.of("errorCode","TOOL_EXECUTION_UNKNOWN"):child.receipt();
            tx.executeWithoutResult(s->port.running(child.runId(),owner));publish(root.runId(),"CHILD_RUN_STARTED",Map.of("childRunId",child.runId(),"toolCallId",call));
            execute(child.runId());var settled=port.get(child.runId());snapshot=null;
            if(journal.get(child.runId()).status()==RunStatus.SUCCEEDED&&op.equals("REWRITE_CHAPTER"))pendingCard="修改候选已生成，请核对下方目标章节及候选正文后点击确认写入。确认前原章节不变。";
            if(journal.get(child.runId()).status()!=RunStatus.SUCCEEDED){generationFailed=true;uncertain=journal.get(child.runId()).status()==RunStatus.UNKNOWN;throw new WritingRuleException(409,settled.receipt()==null?"TOOL_EXECUTION_UNKNOWN":settled.receipt().path("errorCode").asText("TOOL_FAILED"));}return settled.receipt();
        }
    }
    private Map<String,Object> generatePlan(AiRunPort.Execution e){
        var expected=e.request().path("snapshot").path("project");
        var result=planAgent.run(e.projectId(),e.actionId(),projects.currentResearchGoal(e.projectId()),e.request().path("instruction").asText(""),ignored->{},e.runId());
        if(PlanOutcome.CLARIFICATION_REQUIRED.equals(result.outcome().outcome))throw new WritingRuleException(409,"CLARIFICATION_REQUIRED");
        var body=result.outcome().plan;var payload=JSON.valueToTree(body);((com.fasterxml.jackson.databind.node.ObjectNode)payload).remove("chapterOutline");
        var missing=new ArrayList<String>();payload.fields().forEachRemaining(f->{if(f.getValue().isNull())missing.add(f.getKey());});missing.forEach(((com.fasterxml.jackson.databind.node.ObjectNode)payload)::remove);
        var chapters=JSON.createArrayNode();body.chapterOutline.forEach(title->chapters.addObject().put("title",title).put("brief",""));((com.fasterxml.jackson.databind.node.ObjectNode)payload).set("chapters",chapters);
        return tx.execute(s->{journal.guardBusinessCommit(e.runId());var receipt=writing.saveGeneratedPlan(e.projectId(),new WritingCommands.PlanSave("ai|"+e.actionId()+"|plan",expected.path("projectVersion").asLong(),nullableUuid(expected.get("currentPlanRevisionId")),payload));publish(e.runId(),"ARTIFACT_SAVED",receipt);return receipt;});
    }
    private Map<String,Object> generateChapter(AiRunPort.Execution e,UUID key,boolean rewrite){
        Map<String,Object> state="DRAFT_MISSING_CHAPTERS".equals(e.request().path("operation").asText())?writing.workbench(e.projectId()):JSON.convertValue(e.request().path("snapshot"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        var article=map(state.get("article"));var plan=map(state.get("plan"));
        if(!"CONFIRMED".equals(plan.get("status")))throw new WritingRuleException(409,"PLAN_NOT_CONFIRMED");
        if("COMPLETE".equals(article.get("status")))throw new WritingRuleException(409,"ARTICLE_COMPLETE");
        if(e.request().has("approvedArticleVersion")&&((Number)article.get("articleVersion")).longValue()!=e.request().path("approvedArticleVersion").asLong())throw new WritingRuleException(409,"STALE_CONFIRMATION");
        var chapters=list(article.get("chapters"));var target=chapters.stream().filter(c->key.toString().equals(c.get("chapterKey").toString())).findFirst().orElseThrow(()->new WritingRuleException(404,"CHAPTER_NOT_FOUND"));
        if(!rewrite&&target.get("chapterRevisionId")!=null&&!String.valueOf(target.get("bodyMarkdown")).isBlank())return Map.of("outcomeCode","SKIPPED_ALREADY_PRESENT","chapterKey",key);
        var outline=chapters.stream().map(c->c.get("title").toString()).toList();
        var prior=chapters.stream().filter(c->((Number)c.get("chapterIndex")).intValue()<((Number)target.get("chapterIndex")).intValue()).map(c->new ReadConfirmedOutlineTool.PriorChapter(((Number)c.get("chapterIndex")).intValue(),c.get("title").toString(),String.valueOf(c.get("bodyMarkdown")))).toList();
        var result=chapterAgent.run(e.projectId(),e.actionId(),uuid(article.get("articleId")),((Number)target.get("chapterIndex")).intValue(),target.get("title").toString(),projects.currentResearchGoal(e.projectId()),outline,prior,e.request().path("instruction").asText(""),rewrite?String.valueOf(target.get("bodyMarkdown")):null,ignored->{},e.runId());
        var doc=JSON.createObjectNode().put("type","doc");var content=doc.putArray("content");
        // Preserve generated prose as paragraphs; Markdown source is not accepted as executable HTML.
        for(String paragraph:result.outcome().bodyMarkdown.split("\\n\\s*\\n"))if(!paragraph.isBlank())content.addObject().put("type","paragraph").putArray("content").addObject().put("type","text").put("text",paragraph);
        return tx.execute(s->{journal.guardBusinessCommit(e.runId());
            if("BATCH_REWRITE_CANDIDATE".equals(e.request().path("operation").asText())){var card=batches.prepareCandidate(uuid(e.request().path("batchId").asText()),uuid(e.request().path("itemId").asText()),doc,e.runId());publish(e.runId(),"CONFIRMATION_REQUIRED",card);return card;}
            if(rewrite){var card=writing.prepareChapterCandidate(e.projectId(),"candidate|"+e.actionId(),key,uuid(plan.get("planRevisionId")),nullableUuid(target.get("chapterRevisionId")),((Number)article.get("articleVersion")).longValue(),doc,e.request().path("instruction").asText(),e.conversationId(),e.runId());publish(e.runId(),"CONFIRMATION_REQUIRED",card);return card;}
            var receipt=writing.saveGeneratedChapter(e.projectId(),key,new WritingCommands.ChapterSave("ai|"+e.actionId()+"|"+key,uuid(plan.get("planRevisionId")),nullableUuid(target.get("chapterRevisionId")),((Number)article.get("articleVersion")).longValue(),doc));publish(e.runId(),"ARTIFACT_SAVED",receipt);return receipt;});
    }
    private void fail(AiRunPort.Execution e,String code,boolean uncertain){
        fail(e,code,uncertain,"");
    }
    private void fail(AiRunPort.Execution e,String code,boolean uncertain,String errorClass){
        tx.executeWithoutResult(s->{var receipt=JSON.valueToTree(Map.of("errorCode",code,"errorClass",errorClass,"resultMayExist",uncertain,"hasSavedArtifacts",hasArtifact(e.runId())));journal.settle(e.runId(),uncertain?RunStatus.UNKNOWN:RunStatus.FAILED,modelCount(e.runId()),code,uncertain);port.finish(e,receipt,false,uncertain);if("BATCH_REWRITE_CANDIDATE".equals(e.request().path("operation").asText()))batches.generationFailed(uuid(e.request().path("batchId").asText()),e.runId(),uncertain);});
    }
    private int modelCount(UUID run){int count=0;long cursor=0;while(true){var page=journal.page(run,cursor,256);count+=page.stream().filter(e->"MODEL_STARTED".equals(e.outputType())).count();if(page.size()<256)return count;cursor=page.get(page.size()-1).sequence();}}
    private void publish(UUID run,String type,Map<String,Object> payload){journal.append(run,new RunJournal.Draft("stage:"+type+":"+UUID.randomUUID(),type,type,null,null,true,payload));}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){if(value==null)throw new WritingRuleException(409,"PLAN_NOT_CONFIRMED");return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    private static UUID uuid(Object value){try{return UUID.fromString(value.toString());}catch(Exception ex){throw new WritingRuleException(400,"INVALID_REQUEST");}}
    private static UUID nullableUuid(Object value){return value==null||value instanceof JsonNode n&&n.isNull()?null:uuid(value instanceof JsonNode n?n.asText():value);}
    @PreDestroy void close(){continuations.shutdown();workers.shutdown();}
}
