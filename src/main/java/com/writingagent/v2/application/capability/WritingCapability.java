package com.writingagent.v2.application.capability;

import static com.writingagent.v2.domain.DocumentJsonSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.writingagent.v2.application.port.WritingDocumentPort;
import com.writingagent.v2.application.port.WritingDocumentPort.*;
import com.writingagent.v2.application.support.RequestDigests;
import com.writingagent.v2.domain.WritingRuleException;
import com.writingagent.v2.domain.document.ChapterDocumentCodec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared writing rules: no SDK/SQL, one transaction for version + artifact + fixed receipt. */
@Service
public class WritingCapability {
    private final WritingDocumentPort port;
    private final TransactionTemplate tx;
    private final com.writingagent.v2.application.port.BatchRewritePort batches;
    private final String owner;
    private final ChapterDocumentCodec codec=new ChapterDocumentCodec();
    public WritingCapability(WritingDocumentPort port,TransactionTemplate tx,com.writingagent.v2.application.port.BatchRewritePort batches,
            @Value("${wa2.local-owner-scope:local-fixture}") String owner) { this.port=port;this.tx=tx;this.batches=batches;this.owner=owner; }

    public Map<String,Object> project(UUID id) { return projectView(access(port.project(id,false))); }
    public Map<String,Object> lookup(UUID workspace,String scope,String key) {
        requireWorkspace(workspace);
        if(scope==null||!scope.startsWith("project:"))throw error(400,"INVALID_REQUEST");
        UUID project;try{project=UUID.fromString(scope.substring(8));}catch(IllegalArgumentException e){throw error(400,"INVALID_REQUEST");}
        var p=access(port.project(project,false));if(!p.workspaceId().equals(workspace))throw error(403,"TARGET_SCOPE_REJECTED");
        var action=port.action(workspace,project,key).orElseThrow(()->error(404,"ACTION_NOT_FOUND"));
        if(action.receipt()==null)throw error(409,"RUN_STILL_ACTIVE");
        return JSON.convertValue(action.receipt(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    }
    public void requireWorkspace(UUID workspace) {if(!owner.equals(port.workspaceOwner(workspace)))throw error(403,"OWNERSHIP_REJECTED");}
    public List<Map<String,Object>> projects(UUID workspace) {
        if(!owner.equals(port.workspaceOwner(workspace)))throw error(403,"OWNERSHIP_REJECTED");
        return port.projects(workspace,owner).stream().map(this::projectView).toList();
    }
    public Map<String,Object> plan(UUID project,UUID revision) {
        access(port.project(project,false));var p=port.plan(revision).orElseThrow(()->error(404,"PLAN_NOT_FOUND"));
        if(!p.projectId().equals(project))throw error(403,"TARGET_SCOPE_REJECTED");return planView(p);
    }
    public Map<String,Object> article(UUID project) {
        return tx.execute(s->{access(port.project(project,true));var a=port.article(project,false).orElseThrow(()->error(404,"ARTICLE_NOT_CREATED"));return articleView(a);});
    }
    public Map<String,Object> chapter(UUID project,UUID key) {
        return tx.execute(s->{access(port.project(project,true));var a=requireArticle(project,false);return chapterView(findChapter(a,key));});
    }
    public Map<String,Object> proposal(UUID id) {
        return tx.execute(s->proposalProjection(id));
    }
    private Map<String,Object> proposalProjection(UUID id) {
        var p=port.proposal(id,false).orElseThrow(()->error(404,"PROPOSAL_NOT_FOUND"));access(port.project(p.projectId(),false));
        if(!owner.equals(p.requestedBy()))throw error(403,"OWNERSHIP_REJECTED");var view=proposalView(p);view.put("conversationId",p.conversationId());view.put("sourceRunId",p.sourceRunId());
        if("OPEN".equals(p.status())){
            if(p.expiresAt().isBefore(Instant.now()))view.put("status","EXPIRED");
            else {var current=access(port.project(p.projectId(),false));var article=port.article(p.projectId(),false).orElse(null);
                if(!Objects.equals(p.projectVersion(),current.version())||p.planId()!=null&&!Objects.equals(p.planId(),current.planId())||p.articleVersion()!=null&&(article==null||!Objects.equals(p.articleVersion(),article.version())))view.put("status","STALE");}
        }
        String status=(String)view.get("status");
        view.put("storedStatus",p.status());view.put("effectiveStatus",status);view.put("checkedAt",Instant.now());
        view.put("targetLabel",port.project(p.projectId(),false).title());
        view.put("allowedDecisions","OPEN".equals(status)?List.of("APPROVE","REJECT"):List.of());
        view.put("statusLabel",switch(status){case "OPEN"->"待你确认";case "APPROVED"->"已确认";case "REJECTED"->"已拒绝";case "EXPIRED"->"已过期";case "STALE"->"内容已变化";default->"无法核验";});
        view.put("disabledReasonCode","OPEN".equals(status)?null:status);
        view.put("disabledReasonText",switch(status){case "OPEN"->null;case "APPROVED"->"本卡已确认，请查看固定回执，不能重复批准。";case "REJECTED"->"本卡已拒绝，没有执行本卡请求的业务改变。";case "EXPIRED"->"确认卡已过期，不能批准；可按最新内容重新发起确认。";case "STALE"->"目标内容已更新，请重新核对并发起确认。";default->"暂时无法核验，不能提交决定。";});
        view.put("effectDescription",switch(p.type()){case "CONFIRM_PLAN"->"确认当前计划并建立章节；不会自动生成正文。";case "COMPLETE_ARTICLE"->"将当前文章标记为完成，完成后正文只读。";case "CONFIRM_CHAPTER"->"确认后仅写入下面的目标章节候选，其它章节不变。";default->p.snapshot().has("continuation")?"重新打开文章后，自动生成本次修改候选；正文仍须在下一张卡确认后才写入。":"重新打开当前文章，使正文可继续编辑。";});
        if(p.snapshot().has("candidate"))view.put("candidate",p.snapshot().get("candidate"));
        if(p.snapshot().has("continuation"))view.put("continuation",p.snapshot().get("continuation"));
        view.put("targetSnapshot",p.snapshot());
        if("OPEN".equals(status)&&batches.decisionBlocked(id)){view.put("allowedDecisions",List.of());view.put("disabledReasonCode","BATCH_NOT_ACCEPTING_DECISION");view.put("disabledReasonText","本批次已暂停、停止或结果未知；当前卡不可写。暂停时可先恢复批次，再审阅候选。");}
        return view;
    }
    public Map<String,Object> workbench(UUID project) {
        return tx.execute(s->{var p=access(port.project(project,true));var out=new LinkedHashMap<String,Object>();out.put("project",projectView(p));out.put("plan",p.planId()==null?null:planView(port.plan(p.planId()).orElseThrow()));out.put("article",port.article(project,false).map(this::articleView).orElse(null));out.put("proposals",List.of());out.put("activeRuns",List.of());return out;});
    }

    public Map<String,Object> savePlan(UUID id,WritingCommands.PlanSave c) {
        return savePlan(id,c,false);
    }
    public Map<String,Object> saveGeneratedPlan(UUID id,WritingCommands.PlanSave c) {
        return savePlan(id,c,true);
    }
    private Map<String,Object> savePlan(UUID id,WritingCommands.PlanSave c,boolean generated) {
        requireVersion(c.expectedProjectVersion());
        return command(id,c.clientActionId(),generated?"GENERATE_PLAN":"SAVE_PLAN",JSON.valueToTree(c),(p,action)->{
            if(generated&&batches.hasActive(id))throw error(409,"BATCH_WRITE_BUSY");
            editable(p);same(p.version(),c.expectedProjectVersion());same(p.planId(),c.expectedPlanRevisionId());
            JsonNode payload=normalizePlan(p,c.payload());
            var saved=port.insertPlan(id,payload,RequestDigests.sha256(canonical(payload).toString()),generated?"USER_GOAL_AND_MODEL_SYNTHESIS":"USER_AUTHORED");
            var receipt=receipt(action,id,"PLAN_SAVED");receipt.put("planRevisionId",saved.id());receipt.put("revisionNo",saved.revision());
            receipt.put("artifacts",List.of(artifact("PLAN",saved.id(),id)));return receipt;
        });
    }
    public Map<String,Object> saveChapter(UUID id,UUID key,WritingCommands.ChapterSave c) {
        return saveChapter(id,key,c,false);
    }
    public Map<String,Object> saveGeneratedChapter(UUID id,UUID key,WritingCommands.ChapterSave c) {
        return saveChapter(id,key,c,true);
    }
    private Map<String,Object> saveChapter(UUID id,UUID key,WritingCommands.ChapterSave c,boolean generated) {
        requireVersion(c.expectedArticleVersion());if(c.expectedPlanRevisionId()==null)throw error(428,"EXPECTED_VERSION_REQUIRED");
        return command(id,c.clientActionId(),generated?"GENERATE_CHAPTER":"SAVE_CHAPTER",target(key,JSON.valueToTree(c)),(p,action)->{
            if(generated&&batches.hasActive(id)){UUID proposal=null;try{if(c.clientActionId().startsWith("apply-candidate:"))proposal=UUID.fromString(c.clientActionId().substring("apply-candidate:".length()));}catch(IllegalArgumentException ignored){}if(proposal==null||!batches.ownsProposal(id,proposal))throw error(409,"BATCH_WRITE_BUSY");}
            var a=requireArticle(id,true);editable(p);confirmed(p,a,c.expectedPlanRevisionId());if(!generated)same(a.version(),c.expectedArticleVersion());
            var chapter=findChapter(a,key);same(chapter.revisionId(),c.expectedChapterRevisionId());
            ChapterDocumentCodec.EncodedDocument document;
            try { document=codec.encode(write(c.contentJson()),generated); } catch(IllegalArgumentException e) { throw error(400,e.getMessage()); }
            var saved=port.saveChapter(a,chapter,action,parse(document.contentJson()),document.bodyMarkdown(),document.documentHash(),RequestDigests.sha256(document.bodyMarkdown()),generated?"USER_GOAL_AND_MODEL_SYNTHESIS":"USER_EDIT");
            int n=countNonEmpty(port.chapters(a.id()));port.refreshArticle(a.id(),n,articleStatus(n,a.chapterCount()));
            var receipt=receipt(action,id,"CHAPTER_SAVED");receipt.put("chapterRevisionId",saved.revisionId());receipt.put("documentHash",saved.documentHash());receipt.put("revisionNo",saved.revision());receipt.put("articleVersion",a.version()+1);
            var artifact=artifact("CHAPTER",saved.revisionId(),id);artifact.put("chapterKey",key);artifact.put("revisionId",saved.revisionId());artifact.put("revisionNo",saved.revision());artifact.put("documentHash",saved.documentHash());receipt.put("artifacts",List.of(artifact));return receipt;
        });
    }
    public Map<String,Object> saveMetadata(UUID id,WritingCommands.MetadataSave c) {
        return saveMetadata(id,c,false);
    }
    public Map<String,Object> saveGeneratedMetadata(UUID id,WritingCommands.MetadataSave c) {
        return saveMetadata(id,c,true);
    }
    private Map<String,Object> saveMetadata(UUID id,WritingCommands.MetadataSave c,boolean generated) {
        requireVersion(c.expectedArticleVersion());
        return command(id,c.clientActionId(),"SAVE_METADATA",JSON.valueToTree(c),(p,action)->{
            if(generated&&batches.hasActive(id))throw error(409,"BATCH_WRITE_BUSY");
            editable(p);var a=requireArticle(id,true);same(a.version(),c.expectedArticleVersion());same(a.metadataId(),c.expectedMetadataRevisionId());
            JsonNode payload=c.payload();if(payload==null||!payload.isObject())throw error(400,"METADATA_INVALID");
            fields(payload,Set.of("abstract","keywords"));
            if(payload.has("abstract")&&(!payload.get("abstract").isTextual()||payload.get("abstract").asText().length()>8000))throw error(400,"METADATA_INVALID");
            if(payload.has("keywords")){if(!payload.get("keywords").isArray()||payload.get("keywords").size()>20)throw error(400,"METADATA_INVALID");for(var k:payload.get("keywords"))if(!k.isTextual()||k.asText().isBlank()||k.asText().length()>80)throw error(400,"METADATA_INVALID");}
            var normalized=JSON.createObjectNode();normalized.put("abstract",payload.path("abstract").asText(""));normalized.set("keywords",payload.has("keywords")?payload.get("keywords"):JSON.createArrayNode());
            UUID saved=port.saveMetadata(a.id(),normalized,action,generated?"USER_GOAL_AND_MODEL_SYNTHESIS":"USER_AUTHORED");var receipt=receipt(action,id,"ARTICLE_METADATA_SAVED");receipt.put("articleVersion",a.version()+1);receipt.put("artifacts",List.of(artifact("ARTICLE_METADATA",saved,id)));return receipt;
        });
    }
    public Map<String,Object> prepare(UUID id,WritingCommands.ProposalPrepare c) {
        if(!"HTTP".equals(c.origin()))throw error(400,"INVALID_REQUEST");return prepareTrusted(id,c);
    }
    public Map<String,Object> prepareAgent(UUID id,WritingCommands.ProposalPrepare c) {
        if(!"AGENT".equals(c.origin()))throw error(400,"INVALID_REQUEST");return prepareTrusted(id,c);
    }
    public Map<String,Object> prepareAgent(UUID id,WritingCommands.ProposalPrepare c,UUID conversation,UUID run) {
        if(!"AGENT".equals(c.origin())||conversation==null||run==null)throw error(400,"INVALID_REQUEST");
        return prepareTrusted(id,c,conversation,run);
    }
    /** The target and original instruction survive refresh/restart; approval only reopens. */
    public Map<String,Object> prepareRewriteReopen(UUID id,String key,UUID chapter,String instruction,UUID conversation,UUID run){
        return tx.execute(s->{var p=access(port.project(id,true));var a=requireArticle(id,true);var target=findChapter(a,chapter);
            if(instruction==null||instruction.isBlank()||instruction.length()>12000)throw error(400,"REWRITE_INSTRUCTION_REQUIRED");
            var continuation=JSON.createObjectNode().put("chapterKey",chapter.toString()).put("chapterIndex",target.index()).put("instruction",instruction);
            return prepareTrusted(id,new WritingCommands.ProposalPrepare(key,"REOPEN_ARTICLE","AGENT",p.planId(),p.version(),a.version()),conversation,run,continuation);
        });
    }
    public List<UUID> approvedRewriteContinuations(){return port.approvedRewriteContinuations();}
    public Map<String,Object> prepareBatchReopen(UUID project,UUID batch,JsonNode targets,String instruction,UUID conversation,UUID run){
        var p=project(project);var a=requireArticle(project,true);var intent=JSON.createObjectNode().put("batchId",batch.toString()).put("instruction",instruction);intent.set("targets",targets);
        return prepareTrusted(project,new WritingCommands.ProposalPrepare("batch-reopen:"+batch,"REOPEN_ARTICLE",conversation==null?"HTTP":"AGENT",a.planId(),((Number)p.get("projectVersion")).longValue(),a.version()),conversation,run,intent);
    }
    /** Immutable candidate in a proposal snapshot; never alters the CURRENT chapter. */
    public Map<String,Object> prepareChapterCandidate(UUID id,String key,UUID chapter,UUID plan,UUID prior,long version,JsonNode content,String instruction,UUID conversation,UUID run){
        var receipt=command(id,key,"PREPARE_CHAPTER_CANDIDATE",JSON.valueToTree(List.of(chapter,plan,prior==null?"":prior,version,content,instruction)),(p,action)->{
            if(batches.hasActive(id)&&!batches.acceptsCandidate(id,key,run))throw error(409,"BATCH_WRITE_BUSY");
            if((conversation==null)!=(run==null))throw error(400,"INVALID_REQUEST");
            if(conversation!=null&&!port.validProposalContext(p.workspaceId(),id,conversation,run))throw error(403,"TARGET_SCOPE_REJECTED");
            var a=requireArticle(id,true);editable(p);confirmed(p,a,plan);same(a.version(),version);var target=findChapter(a,chapter);same(target.revisionId(),prior);
            ChapterDocumentCodec.EncodedDocument encoded;try{encoded=codec.encode(write(content),true);}catch(IllegalArgumentException ex){throw error(400,ex.getMessage());}
            com.writingagent.v2.domain.ChapterInstructionContract.validateCandidate(instruction,content,target.index());
            var snapshot=JSON.createObjectNode();snapshot.set("manifest",manifest(a));var candidate=snapshot.putObject("candidate");
            candidate.put("chapterKey",chapter.toString()).put("chapterIndex",target.index()).put("title",target.title()).put("instruction",instruction).put("bodyMarkdown",encoded.bodyMarkdown()).put("documentHash",encoded.documentHash());
            candidate.set("expectedChapterRevisionId",JSON.valueToTree(prior));candidate.set("contentJson",parse(encoded.contentJson()));
            var proposal=port.insertProposal(p,a,plan,"CONFIRM_CHAPTER",conversation==null?"HTTP":"AGENT",owner,snapshot,conversation,run);
            var r=receipt(action,id,"CHAPTER_CANDIDATE_PREPARED");r.put("artifacts",List.of(artifact("PROPOSAL",proposal.id(),id)));return r;
        });
        return proposal(UUID.fromString(((Map<?,?>)((List<?>)receipt.get("artifacts")).get(0)).get("artifactId").toString()));
    }
    private Map<String,Object> prepareTrusted(UUID id,WritingCommands.ProposalPrepare c) {
        return prepareTrusted(id,c,null,null);
    }
    private Map<String,Object> prepareTrusted(UUID id,WritingCommands.ProposalPrepare c,UUID conversation,UUID run) {
        return prepareTrusted(id,c,conversation,run,null);
    }
    private Map<String,Object> prepareTrusted(UUID id,WritingCommands.ProposalPrepare c,UUID conversation,UUID run,JsonNode continuation) {
        requireVersion(c.expectedProjectVersion());
        var input=JSON.createObjectNode();input.set("command",JSON.valueToTree(c));input.put("conversationId",conversation==null?null:conversation.toString());input.put("sourceRunId",run==null?null:run.toString());
        if(continuation!=null)input.set("continuation",continuation);
        var receipt=command(id,c.clientActionId(),"PREPARE_PROPOSAL",conversation==null?JSON.valueToTree(c):input,(p,action)->{
            if(conversation!=null&&!port.validProposalContext(p.workspaceId(),id,conversation,run))throw error(403,"TARGET_SCOPE_REJECTED");
            same(p.version(),c.expectedProjectVersion());var a=port.article(id,true).orElse(null);UUID planId=null;
            if("CONFIRM_PLAN".equals(c.type())) {
                editable(p);same(p.planId(),c.targetPlanRevisionId());if(c.targetPlanRevisionId()==null)throw error(400,"INVALID_REQUEST");
                var plan=port.plan(c.targetPlanRevisionId()).orElseThrow(()->error(404,"PLAN_NOT_FOUND"));if(!"DRAFT".equals(plan.status()))throw error(409,"STALE_CONFIRMATION");planId=plan.id();
            } else if("COMPLETE_ARTICLE".equals(c.type())) {
                if(a==null)throw error(404,"ARTICLE_NOT_CREATED");requireVersion(c.expectedArticleVersion());same(a.version(),c.expectedArticleVersion());editable(p);confirmed(p,a,a.planId());
                if(countNonEmpty(port.chapters(a.id()))!=a.chapterCount())throw error(409,"ARTICLE_INCOMPLETE");
            } else if("REOPEN_ARTICLE".equals(c.type())) {
                if(a==null)throw error(404,"ARTICLE_NOT_CREATED");requireVersion(c.expectedArticleVersion());same(a.version(),c.expectedArticleVersion());if(!"COMPLETE".equals(a.status()))throw error(409,"STALE_CONFIRMATION");
            } else throw error(400,"INVALID_REQUEST");
            JsonNode snapshot=a==null?JSON.createObjectNode():manifest(a);
            if(continuation!=null){var wrapped=JSON.createObjectNode();wrapped.set("manifest",snapshot);wrapped.set("continuation",continuation);snapshot=wrapped;}
            var proposal=port.insertProposal(p,a,planId,c.type(),c.origin(),owner,snapshot,conversation,run);
            var r=receipt(action,id,"CONFIRMATION_PREPARED");r.put("artifacts",List.of(artifact("PROPOSAL",proposal.id(),id)));return r;
        });
        var artifacts=(List<?>)receipt.get("artifacts");var artifact=(Map<?,?>)artifacts.get(0);return proposal(UUID.fromString(artifact.get("artifactId").toString()));
    }
    public List<Map<String,Object>> conversationProposals(UUID conversation,int offset,int limit) {
        if(offset<0||limit<1||limit>100)throw error(400,"INVALID_REQUEST");
        return tx.execute(s->port.conversationProposals(conversation,offset,limit).stream().map(this::proposal).toList());
    }
    public Map<String,Object> decide(UUID proposalId,WritingCommands.ProposalDecision c) {
        if(!Set.of("APPROVE","REJECT").contains(c.decision()==null?"":c.decision()))throw error(400,"INVALID_REQUEST");
        var hint=port.proposal(proposalId,false).orElseThrow(()->error(404,"PROPOSAL_NOT_FOUND"));
        return command(hint.projectId(),c.clientActionId(),"DECIDE_PROPOSAL",target(proposalId,JSON.valueToTree(c)),(p,action)->{
            var a=port.article(p.id(),true).orElse(null);var proposal=port.proposal(proposalId,true).orElseThrow();
            if(!owner.equals(proposal.requestedBy()))throw error(403,"OWNERSHIP_REJECTED");
            if(!"OPEN".equals(proposal.status()))throw error(409,"PROPOSAL_ALREADY_DECIDED");
            batches.guardDecision(proposalId);
            String failure=null;
            if(!proposal.expiresAt().isAfter(Instant.now()))failure="PROPOSAL_EXPIRED";
            else if(p.version()!=proposal.projectVersion()||!Objects.equals(a==null?null:a.version(),proposal.articleVersion())
                    ||("CONFIRM_PLAN".equals(proposal.type())&&!Objects.equals(p.planId(),proposal.planId()))
                    ||(a!=null&&!canonical(manifest(a)).equals(canonical(proposal.snapshot().has("manifest")?proposal.snapshot().get("manifest"):proposal.snapshot())))) failure="STALE_CONFIRMATION";
            if(failure!=null) {
                var failed=failedReceipt(action,p.id(),failure);String failedStatus="PROPOSAL_EXPIRED".equals(failure)?"EXPIRED":"STALE";port.decideProposal(proposalId,failedStatus,action,JSON.valueToTree(failed));batches.decisionRecorded(proposalId,failedStatus,JSON.valueToTree(failed),a==null?JSON.createObjectNode():rewriteManifest(p.id()));return failed;
            }
            var result=receipt(action,p.id(),"PROPOSAL_REJECTED");
            if("APPROVE".equals(c.decision())) {
                if("CONFIRM_PLAN".equals(proposal.type())) {
                    var plan=port.plan(proposal.planId()).orElseThrow();editable(p);validateOutlineAgainstBody(p,plan.payload());
                    port.confirmPlan(plan.id(),owner);port.syncArticle(p,plan);result.put("outcomeCode","PLAN_CONFIRMED");result.put("planRevisionId",plan.id());
                } else if("COMPLETE_ARTICLE".equals(proposal.type())) {
                    confirmed(p,a,a.planId());if(countNonEmpty(port.chapters(a.id()))!=a.chapterCount())throw error(409,"ARTICLE_INCOMPLETE");
                    port.complete(a.id(),proposal.snapshot());result.put("outcomeCode","ARTICLE_COMPLETED");result.put("articleVersion",a.version()+1);
                } else if("CONFIRM_CHAPTER".equals(proposal.type())) {
                    var candidate=proposal.snapshot().path("candidate");
                    var saved=saveGeneratedChapter(p.id(),UUID.fromString(candidate.path("chapterKey").asText()),new WritingCommands.ChapterSave("apply-candidate:"+proposalId,a.planId(),candidate.path("expectedChapterRevisionId").isNull()?null:UUID.fromString(candidate.path("expectedChapterRevisionId").asText()),a.version(),candidate.path("contentJson")));
                    result.putAll(saved);result.put("actionId",action);result.put("operationId",action);result.put("proposalId",proposalId);
                } else if("REOPEN_ARTICLE".equals(proposal.type())) {int count=countNonEmpty(port.chapters(a.id()));port.reopen(a.id(),articleStatus(count,a.chapterCount()),count);result.put("outcomeCode","ARTICLE_REOPENED");result.put("articleVersion",a.version()+1);}
                else throw error(400,"INVALID_REQUEST");
            }
            String decided="APPROVE".equals(c.decision())?"APPROVED":"REJECTED";
            port.decideProposal(proposalId,decided,action,JSON.valueToTree(result));
            batches.decisionRecorded(proposalId,decided,JSON.valueToTree(result),a==null?JSON.createObjectNode():rewriteManifest(p.id()));return result;
        });
    }

    private Map<String,Object> command(UUID id,String key,String operation,JsonNode request,BiFunction<Project,UUID,Map<String,Object>> work) {
        if(key==null||key.isBlank()||key.length()>300)throw error(400,"INVALID_REQUEST");
        String digest=RequestDigests.sha256(operation+"|"+id+"|"+canonical(request));
        boolean joined=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        Map<String,Object> result;
        try {
            result=tx.execute(s->{var p=access(port.project(id,true));var action=port.claim(p.workspaceId(),id,key,digest,operation);
                if(!action.digest().equals(digest))throw error(409,"IDEMPOTENCY_KEY_REUSED");
                if(action.receipt()!=null)return replay(action.receipt());if(!action.claimed())throw error(409,"RUN_STILL_ACTIVE");
                var r=work.apply(p,action.id());port.settle(action.id(),JSON.valueToTree(r),"SUCCEEDED".equals(r.get("actionStatus")));return r;});
        } catch(WritingRuleException failure) {
            // When called by an Agent artifact transaction, let that transaction roll back first.
            // Retrying claim inside the same rollback-only transaction masks the real rejection.
            if(joined)throw failure;
            // Business mutations have rolled back. Record a deterministic failure in a NEW transaction.
            if(Set.of("OWNERSHIP_REJECTED","PROJECT_NOT_FOUND","IDEMPOTENCY_KEY_REUSED","RUN_STILL_ACTIVE").contains(failure.code()))throw failure;
            result=tx.execute(s->{var p=access(port.project(id,true));var action=port.claim(p.workspaceId(),id,key,digest,operation);
                if(!action.digest().equals(digest))throw error(409,"IDEMPOTENCY_KEY_REUSED");if(action.receipt()!=null)return replay(action.receipt());
                if(!action.claimed())throw error(409,"RUN_STILL_ACTIVE");var r=failedReceipt(action.id(),id,failure.code());port.settle(action.id(),JSON.valueToTree(r),false);return r;});
            if("FAILED".equals(result.get("actionStatus")))throw failure;
        }
        if("FAILED".equals(result.get("actionStatus"))){String code=result.get("errorCode").toString();throw error(rejectionStatus(code),code);}return result;
    }
    private JsonNode normalizePlan(Project project,JsonNode input) {
        if(input==null||!input.isObject())throw error(400,"PLAN_SCHEMA_INVALID");
        fields(input,Set.of("researchQuestion","goals","scope","methodSuggestion","expectedDeliverables","risksOrAssumptions","chapters"));
        var payload=(ObjectNode)input.deepCopy();
        for(String k:List.of("researchQuestion","goals","scope","methodSuggestion","expectedDeliverables"))if(payload.has(k)&&(!payload.get(k).isTextual()||payload.get(k).asText().length()>4000))throw error(400,"PLAN_SCHEMA_INVALID");
        JsonNode risks=payload.get("risksOrAssumptions");if(risks!=null){if(!risks.isArray()||risks.size()>20)throw error(400,"PLAN_SCHEMA_INVALID");for(var r:risks)if(!r.isTextual()||r.asText().length()>1000)throw error(400,"PLAN_SCHEMA_INVALID");}
        JsonNode chapters=payload.path("chapters");if(!chapters.isArray()||chapters.isEmpty()||chapters.size()>20)throw error(400,"PLAN_SCHEMA_INVALID");
        var previous=port.plan(project.planId()).map(p->p.payload().path("chapters")).orElse(JSON.createArrayNode());var known=new HashSet<String>();previous.forEach(ch->known.add(ch.path("chapterKey").asText()));
        var article=port.article(project.id(),false);article.ifPresent(a->port.chapters(a.id()).forEach(ch->known.add(ch.key().toString())));
        var seen=new HashSet<String>();int index=0;
        for(JsonNode ch:chapters) {
            if(!ch.isObject())throw error(400,"PLAN_SCHEMA_INVALID");fields(ch,Set.of("chapterKey","title","brief"));
            if(!ch.path("title").isTextual()||ch.path("title").asText().isBlank()||ch.path("title").asText().length()>120||
                (ch.has("brief")&&(!ch.get("brief").isTextual()||ch.get("brief").asText().length()>2000)))throw error(400,"PLAN_SCHEMA_INVALID");
            String key=ch.path("chapterKey").asText("");
            if(key.isEmpty())key=index<previous.size()?previous.get(index).path("chapterKey").asText(""):"";
            if(key.isEmpty())key=UUID.randomUUID().toString();else if(!known.contains(key))throw error(400,"PLAN_SCHEMA_INVALID");
            try{UUID.fromString(key);}catch(IllegalArgumentException e){throw error(400,"PLAN_SCHEMA_INVALID");}if(!seen.add(key))throw error(400,"PLAN_SCHEMA_INVALID");
            ((ObjectNode)ch).put("chapterKey",key);((ObjectNode)ch).put("brief",ch.path("brief").asText(""));index++;
        }
        validateOutlineAgainstBody(project,payload);payload.put("schemaVersion",2);return payload;
    }
    private void validateOutlineAgainstBody(Project p,JsonNode payload) {
        port.article(p.id(),false).ifPresent(a->{var chapters=port.chapters(a.id());if(chapters.stream().anyMatch(ch->ch.revisionId()!=null)) {
            if(chapters.size()!=payload.path("chapters").size())throw error(409,"OUTLINE_STRUCTURE_LOCKED");for(int i=0;i<chapters.size();i++)if(!chapters.get(i).key().toString().equals(payload.path("chapters").get(i).path("chapterKey").asText()))throw error(409,"OUTLINE_STRUCTURE_LOCKED");
        }});
    }
    private void editable(Project p) {if(port.article(p.id(),false).map(a->"COMPLETE".equals(a.status())).orElse(false))throw error(409,"ARTICLE_COMPLETE");}
    private Project access(Project p) {if(!owner.equals(p.ownerScope()))throw error(403,"OWNERSHIP_REJECTED");return p;}
    private Article requireArticle(UUID p,boolean lock) {return port.article(p,lock).orElseThrow(()->error(404,"ARTICLE_NOT_CREATED"));}
    private Chapter findChapter(Article a,UUID key) {return port.chapters(a.id()).stream().filter(c->c.key().equals(key)).findFirst().orElseThrow(()->error(404,"CHAPTER_NOT_FOUND"));}
    private void confirmed(Project p,Article a,UUID expected) {if(!Objects.equals(p.planId(),a.planId())||!Objects.equals(a.planId(),expected)||!port.plan(a.planId()).map(pl->"CONFIRMED".equals(pl.status())).orElse(false))throw error(409,"PLAN_NOT_CONFIRMED");}
    private boolean nonEmpty(Chapter c) {return c.contentJson()==null?c.markdown()!=null&&!c.markdown().isBlank():codec.encode(write(c.contentJson()),false).hasText();}
    private int countNonEmpty(List<Chapter> c) {return (int)c.stream().filter(this::nonEmpty).count();}
    private String articleStatus(int count,int total) {return count==0?"DRAFTING":count<total?"PARTIAL":"READY_FOR_COMPLETE";}
    private JsonNode manifest(Article a) {var m=JSON.createObjectNode();m.put("planRevisionId",a.planId().toString());m.set("metadataRevisionId",JSON.valueToTree(a.metadataId()));var list=m.putArray("chapters");for(var c:port.chapters(a.id())){var ch=list.addObject();ch.put("chapterKey",c.key().toString());ch.set("chapterRevisionId",JSON.valueToTree(c.revisionId()));ch.set("documentHash",JSON.valueToTree(c.documentHash()));ch.set("contentHash",JSON.valueToTree(c.contentHash()));}return m;}
    public JsonNode rewriteManifest(UUID project){var p=access(port.project(project,false));var a=requireArticle(project,false);var m=(ObjectNode)manifest(a);m.put("projectVersion",p.version());m.put("articleVersion",a.version());return m;}
    private Map<String,Object> projectView(Project p) {var m=new LinkedHashMap<String,Object>();m.put("projectId",p.id());m.put("workspaceId",p.workspaceId());m.put("title",p.title());m.put("projectVersion",p.version());m.put("currentPlanRevisionId",p.planId());m.put("currentArticleId",p.articleId());return m;}
    private Map<String,Object> planView(Plan p) {var m=new LinkedHashMap<String,Object>();m.put("planRevisionId",p.id());m.put("projectId",p.projectId());m.put("revisionNo",p.revision());m.put("status",p.status());var payload=p.payload().deepCopy();if(payload.isObject())((ObjectNode)payload).remove("schemaVersion");m.put("payload",payload);m.put("sourceBasis",p.sourceBasis());m.put("contentHash",p.hash());m.put("confirmedAt",p.confirmedAt());return m;}
    private Map<String,Object> articleView(Article a) {var m=new LinkedHashMap<String,Object>();m.put("articleId",a.id());m.put("projectId",a.projectId());m.put("planRevisionId",a.planId());m.put("articleVersion",a.version());m.put("status",a.status());m.put("chapterCount",a.chapterCount());var chapters=port.chapters(a.id());m.put("completedChapterCount",countNonEmpty(chapters));m.put("chapters",chapters.stream().map(this::chapterView).toList());m.put("metadata",port.metadata(a.metadataId()));m.put("currentMetadataRevisionId",a.metadataId());m.put("completionManifest",a.completionManifest());return m;}
    private Map<String,Object> chapterView(Chapter c) {var m=new LinkedHashMap<String,Object>();m.put("chapterKey",c.key());m.put("chapterIndex",c.index());m.put("title",c.title());m.put("brief",c.brief());m.put("chapterRevisionId",c.revisionId());m.put("revisionNo",c.revision());JsonNode doc=c.contentJson();if(doc==null){var root=JSON.createObjectNode();root.put("type","doc");var paragraph=root.putArray("content").addObject();paragraph.put("type","paragraph");if(c.markdown()!=null&&!c.markdown().isEmpty()){var text=paragraph.putArray("content").addObject();text.put("type","text");text.put("text",c.markdown());}doc=root;}m.put("contentJson",doc);m.put("bodyMarkdown",c.markdown()==null?"":c.markdown());m.put("documentHash",c.documentHash());m.put("contentHash",c.contentHash());m.put("contentFormat",c.revisionId()==null?"EMPTY":c.contentJson()==null?"LEGACY_MARKDOWN":"JSON_V1");m.put("sourceBasis",c.sourceBasis());return m;}
    private Map<String,Object> proposalView(Proposal p) {var m=new LinkedHashMap<String,Object>();m.put("proposalId",p.id());m.put("workspaceId",p.workspaceId());m.put("projectId",p.projectId());m.put("type",p.type());m.put("origin",p.origin());m.put("status",p.status());m.put("targetPlanRevisionId",p.planId());m.put("expectedProjectVersion",p.projectVersion());m.put("expectedArticleVersion",p.articleVersion());m.put("displaySummary",p.type()+" · 版本 "+p.projectVersion());m.put("expiresAt",p.expiresAt());m.put("receipt",p.receipt());m.put("followupToken","CONFIRM_PLAN".equals(p.type())?"plan-followup:"+p.id()+":DRAFT_MISSING_CHAPTERS":null);return m;}
    private static Map<String,Object> receipt(UUID action,UUID project,String code) {var m=new LinkedHashMap<String,Object>();m.put("actionId",action);m.put("operationId",action);m.put("actionStatus","SUCCEEDED");m.put("outcomeCode",code);m.put("rootRunId",null);m.put("projectId",project);m.put("artifacts",List.of());m.put("resultMayExist",false);m.put("errorCode",null);m.put("replayed",false);m.put("recordedAt",Instant.now().toString());return m;}
    private static Map<String,Object> failedReceipt(UUID action,UUID project,String code) {var m=receipt(action,project,code);m.put("actionStatus","FAILED");m.put("errorCode",code);return m;}
    @SuppressWarnings("unchecked") private static Map<String,Object> replay(JsonNode receipt) {Map<String,Object> r=JSON.convertValue(receipt,LinkedHashMap.class);r.put("replayed",true);return r;}
    private static Map<String,Object> artifact(String type,UUID id,UUID project) {var m=new LinkedHashMap<String,Object>();m.put("artifactType",type);m.put("artifactId",id);m.put("projectId",project);return m;}
    private static JsonNode target(UUID key,JsonNode payload) {var n=JSON.createObjectNode();n.put("target",key.toString());n.set("payload",payload);return n;}
    private static void requireVersion(Long v) {if(v==null)throw error(428,"EXPECTED_VERSION_REQUIRED");if(v<0)throw error(400,"INVALID_REQUEST");}
    private static void same(Object a,Object b) {if(!Objects.equals(a,b))throw error(409,"VERSION_CONFLICT");}
    private static void fields(JsonNode n,Set<String> allowed) {n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw error(400,"INVALID_REQUEST");});}
    private static int rejectionStatus(String code) {
        if(code.startsWith("DOCUMENT_")||code.startsWith("UNSUPPORTED_DOCUMENT_")||Set.of("INVALID_REQUEST","PLAN_SCHEMA_INVALID","METADATA_INVALID").contains(code))return 400;
        if("EXPECTED_VERSION_REQUIRED".equals(code))return 428;
        if(code.endsWith("_NOT_FOUND")||"ARTICLE_NOT_CREATED".equals(code))return 404;
        if(Set.of("OWNERSHIP_REJECTED","TARGET_SCOPE_REJECTED").contains(code))return 403;
        return 409;
    }
    private static WritingRuleException error(int status,String code) {return new WritingRuleException(status,code);}
}
