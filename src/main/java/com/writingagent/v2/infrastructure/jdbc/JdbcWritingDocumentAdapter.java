package com.writingagent.v2.infrastructure.jdbc;

import static com.writingagent.v2.domain.DocumentJsonSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.writingagent.v2.application.port.WritingDocumentPort;
import com.writingagent.v2.domain.WritingRuleException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWritingDocumentAdapter implements WritingDocumentPort {
    private final JdbcTemplate jdbc;
    public JdbcWritingDocumentAdapter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Project project(UUID id,boolean lock) {
        var rows=jdbc.queryForList("SELECT p.*,w.owner_scope FROM writing_v2.writing_project p JOIN writing_v2.workspace w USING(workspace_id) WHERE project_id=?"+(lock?" FOR UPDATE OF p":""),id);
        if(rows.isEmpty())throw new WritingRuleException(404,"PROJECT_NOT_FOUND");return project(rows.get(0));
    }
    @Override public String workspaceOwner(UUID id) {
        var rows=jdbc.queryForList("SELECT owner_scope FROM writing_v2.workspace WHERE workspace_id=?",id);
        if(rows.isEmpty())throw new WritingRuleException(404,"WORKSPACE_NOT_FOUND");return (String)rows.get(0).get("owner_scope");
    }
    @Override public List<Project> projects(UUID id,String owner) {
        return jdbc.queryForList("SELECT p.*,w.owner_scope FROM writing_v2.writing_project p JOIN writing_v2.workspace w USING(workspace_id) WHERE workspace_id=? AND owner_scope=? ORDER BY p.updated_at DESC,p.project_id",id,owner).stream().map(JdbcWritingDocumentAdapter::project).toList();
    }
    private static Project project(Map<String,Object> r) { return new Project(uuid(r,"project_id"),uuid(r,"workspace_id"),(String)r.get("owner_scope"),(String)r.get("title"),number(r,"project_version"),uuid(r,"current_plan_revision_id"),uuid(r,"current_article_id")); }
    @Override public Optional<Plan> plan(UUID id) {
        if(id==null)return Optional.empty();return jdbc.queryForList("SELECT * FROM writing_v2.plan_revision WHERE plan_revision_id=?",id).stream().findFirst().map(r->new Plan(id,uuid(r,"project_id"),number(r,"revision_no"),(String)r.get("status"),json(r,"payload_json"),(String)r.get("source_basis"),(String)r.get("content_hash"),instant(r,"confirmed_at")));
    }
    @Override public Optional<Article> article(UUID project,boolean lock) {
        return jdbc.queryForList("SELECT * FROM writing_v2.article WHERE project_id=?"+(lock?" FOR UPDATE":""),project).stream().findFirst().map(r->new Article(uuid(r,"article_id"),project,uuid(r,"plan_revision_id"),number(r,"article_version"),(String)r.get("status"),(int)number(r,"chapter_count"),uuid(r,"current_metadata_revision_id"),json(r,"completion_manifest")));
    }
    @Override public List<Chapter> chapters(UUID article) {
        return jdbc.queryForList("""
            SELECT c.*,r.chapter_revision_id,r.revision_no,r.content_json,r.body_markdown,r.document_hash,r.content_hash,r.source_basis
            FROM writing_v2.article_chapter c LEFT JOIN writing_v2.chapter_revision r
              ON r.article_id=c.article_id AND r.chapter_key=c.chapter_key AND r.status='CURRENT'
            WHERE c.article_id=? ORDER BY c.chapter_index
            """,article).stream().map(r->new Chapter(uuid(r,"chapter_key"),(int)number(r,"chapter_index"),(String)r.get("title"),(String)r.get("brief"),uuid(r,"chapter_revision_id"),number(r,"revision_no"),json(r,"content_json"),(String)r.get("body_markdown"),(String)r.get("document_hash"),(String)r.get("content_hash"),(String)r.get("source_basis"))).toList();
    }
    @Override public JsonNode metadata(UUID id) {
        return id==null?parse("{\"abstract\":\"\",\"keywords\":[]}"):parse(jdbc.queryForObject("SELECT payload_json::text FROM writing_v2.article_metadata_revision WHERE metadata_revision_id=?",String.class,id));
    }
    @Override public Action claim(UUID workspace,UUID project,String key,String digest,String operation) {
        var inserted=jdbc.queryForList("""
            INSERT INTO writing_v2.action_ledger(action_id,workspace_id,project_id,scope_key,client_action_id,request_digest,digest_version,operation,status)
            VALUES(?,?,?,'project:'||?::text,?,?,2,?,'CLAIMED')
            ON CONFLICT(workspace_id,scope_key,client_action_id) DO NOTHING RETURNING action_id
            """,UUID.randomUUID(),workspace,project,project,key,digest,operation);
        var existing=action(workspace,project,key).orElseThrow();
        return new Action(existing.id(),existing.digest(),existing.status(),existing.receipt(),!inserted.isEmpty());
    }
    @Override public Optional<Action> action(UUID w,UUID p,String key) {
        return jdbc.queryForList("SELECT action_id,request_digest,status,receipt_json FROM writing_v2.action_ledger WHERE workspace_id=? AND scope_key='project:'||?::text AND client_action_id=?",w,p,key).stream().findFirst().map(r->new Action(uuid(r,"action_id"),(String)r.get("request_digest"),(String)r.get("status"),json(r,"receipt_json"),false));
    }
    @Override public void settle(UUID id,JsonNode receipt,boolean success) {
        jdbc.update("UPDATE writing_v2.action_ledger SET status=?,receipt_json=?::jsonb,error_code=?,updated_at=now() WHERE action_id=?",success?"RESULT_RECORDED":"FAILED",write(receipt),success?null:receipt.path("errorCode").asText(),id);
    }
    @Override public Plan insertPlan(UUID project,JsonNode payload,String hash,String source) {
        UUID id=UUID.randomUUID();long no=jdbc.queryForObject("SELECT COALESCE(max(revision_no),0)+1 FROM writing_v2.plan_revision WHERE project_id=?",Long.class,project);
        jdbc.update("INSERT INTO writing_v2.plan_revision(plan_revision_id,project_id,revision_no,status,payload_json,content_hash,source_basis) VALUES(?,?,?,'DRAFT',?::jsonb,?,?)",id,project,no,write(payload),hash,source);
        jdbc.update("UPDATE writing_v2.writing_project SET current_plan_revision_id=?,project_version=project_version+1,updated_at=now() WHERE project_id=?",id,project);
        return plan(id).orElseThrow();
    }
    @Override public void confirmPlan(UUID id,String user) { jdbc.update("UPDATE writing_v2.plan_revision SET status='CONFIRMED',confirmed_at=now(),confirmed_by=? WHERE plan_revision_id=?",user,id); }
    @Override public void syncArticle(Project p,Plan plan) {
        UUID article=p.articleId()==null?UUID.randomUUID():p.articleId();var chapters=plan.payload().path("chapters");
        if(p.articleId()==null) {
            jdbc.update("INSERT INTO writing_v2.article(article_id,project_id,plan_revision_id,status,title,chapter_count,evidence_basis) VALUES(?,?,?,'DRAFTING',?,?,'USER_AUTHORED')",article,p.id(),plan.id(),p.title(),chapters.size());
            jdbc.update("UPDATE writing_v2.writing_project SET current_article_id=? WHERE project_id=?",article,p.id());
        } else {
            jdbc.update("UPDATE writing_v2.article SET plan_revision_id=?,chapter_count=?,article_version=article_version+1,updated_at=now() WHERE article_id=?",plan.id(),chapters.size(),article);
            jdbc.update("DELETE FROM writing_v2.article_chapter c WHERE article_id=? AND NOT EXISTS(SELECT 1 FROM writing_v2.chapter_revision r WHERE r.chapter_key=c.chapter_key)",article);
        }
        int index=0;
        for(JsonNode ch:chapters) {
            jdbc.update("INSERT INTO writing_v2.article_chapter(chapter_key,article_id,chapter_index,title,brief) VALUES(?,?,?,?,?) ON CONFLICT(chapter_key) DO UPDATE SET title=excluded.title,brief=excluded.brief",UUID.fromString(ch.path("chapterKey").asText()),article,++index,ch.path("title").asText(),ch.path("brief").asText(""));
        }
        jdbc.update("UPDATE writing_v2.writing_project SET project_version=project_version+1,updated_at=now() WHERE project_id=?",p.id());
    }
    @Override public Chapter saveChapter(Article a,Chapter c,UUID action,JsonNode doc,String markdown,String docHash,String contentHash,String basis) {
        jdbc.update("UPDATE writing_v2.chapter_revision SET status='SUPERSEDED' WHERE article_id=? AND chapter_key=? AND status='CURRENT'",a.id(),c.key());
        long no=jdbc.queryForObject("SELECT COALESCE(max(revision_no),0)+1 FROM writing_v2.chapter_revision WHERE article_id=? AND chapter_key=?",Long.class,a.id(),c.key());UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO writing_v2.chapter_revision(chapter_revision_id,article_id,chapter_index,chapter_key,revision_no,status,title,body_markdown,content_hash,source_basis,source_action_id,content_json,document_hash,codec_version)
            VALUES(?,?,?,?,?,'CURRENT',?,?,?,?,?,?::jsonb,?,1)
            """,id,a.id(),c.index(),c.key(),no,c.title(),markdown,contentHash,basis,action,write(doc),docHash);
        return chapters(a.id()).stream().filter(ch->ch.key().equals(c.key())).findFirst().orElseThrow();
    }
    @Override public void refreshArticle(UUID id,int count,String status) { jdbc.update("UPDATE writing_v2.article SET status=?,completed_chapter_count=?,article_version=article_version+1,updated_at=now() WHERE article_id=?",status,count,id); }
    @Override public UUID saveMetadata(UUID article,JsonNode payload,UUID action,String basis) {
        jdbc.update("UPDATE writing_v2.article_metadata_revision SET status='SUPERSEDED' WHERE article_id=? AND status='CURRENT'",article);
        long no=jdbc.queryForObject("SELECT COALESCE(max(revision_no),0)+1 FROM writing_v2.article_metadata_revision WHERE article_id=?",Long.class,article);UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO writing_v2.article_metadata_revision(metadata_revision_id,article_id,revision_no,status,payload_json,source_basis,source_action_id) VALUES(?,?,?,'CURRENT',?::jsonb,?,?)",id,article,no,write(payload),basis,action);
        jdbc.update("UPDATE writing_v2.article SET current_metadata_revision_id=?,article_version=article_version+1,updated_at=now() WHERE article_id=?",id,article);return id;
    }
    @Override public void complete(UUID id,JsonNode manifest) { jdbc.update("UPDATE writing_v2.article SET status='COMPLETE',completion_manifest=?::jsonb,completed_at=now(),article_version=article_version+1,updated_at=now() WHERE article_id=?",write(manifest),id); }
    @Override public void reopen(UUID id,String status,int count) { jdbc.update("UPDATE writing_v2.article SET status=?,completed_chapter_count=?,completed_at=NULL,article_version=article_version+1,updated_at=now() WHERE article_id=?",status,count,id); }
    @Override public Proposal insertProposal(Project p,Article a,UUID plan,String type,String origin,String user,JsonNode snapshot,UUID conversation,UUID sourceRun) {
        var existing=jdbc.queryForList("SELECT proposal_id FROM writing_v2.confirmation_proposal WHERE project_id=? AND requested_by=? AND origin=? AND proposal_type=? AND expected_project_version=? AND expected_article_version IS NOT DISTINCT FROM ? AND target_revision_id IS NOT DISTINCT FROM ? AND conversation_id IS NOT DISTINCT FROM ? AND target_snapshot=?::jsonb AND status='OPEN' AND expires_at>now() ORDER BY created_at LIMIT 1",p.id(),user,origin,type,p.version(),a==null?null:a.version(),plan,conversation,write(snapshot));
        if(!existing.isEmpty())return proposal(uuid(existing.get(0),"proposal_id"),false).orElseThrow();
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO writing_v2.confirmation_proposal(proposal_id,workspace_id,project_id,requested_by,origin,proposal_type,target_revision_id,expected_project_version,expected_article_version,target_snapshot,status,expires_at,conversation_id,source_run_id) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,'OPEN',now()+interval '10 minutes',?,?)",id,p.workspaceId(),p.id(),user,origin,type,plan,p.version(),a==null?null:a.version(),write(snapshot),conversation,sourceRun);return proposal(id,false).orElseThrow();
    }
    @Override public Optional<Proposal> proposal(UUID id,boolean lock) {
        return jdbc.queryForList("SELECT * FROM writing_v2.confirmation_proposal WHERE proposal_id=?"+(lock?" FOR UPDATE":""),id).stream().findFirst().map(r->new Proposal(id,uuid(r,"workspace_id"),uuid(r,"project_id"),(String)r.get("requested_by"),(String)r.get("origin"),(String)r.get("proposal_type"),(String)r.get("status"),uuid(r,"target_revision_id"),number(r,"expected_project_version"),r.get("expected_article_version")==null?null:number(r,"expected_article_version"),json(r,"target_snapshot"),instant(r,"expires_at"),json(r,"receipt_json"),uuid(r,"conversation_id"),uuid(r,"source_run_id")));
    }
    @Override public boolean validProposalContext(UUID ws,UUID project,UUID conversation,UUID run) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM writing_v2.agent_run r JOIN writing_v2.writing_task t ON t.task_id=r.task_id JOIN writing_v2.conversation c ON c.conversation_id=t.conversation_id WHERE r.run_id=? AND t.conversation_id=? AND t.workspace_id=? AND c.workspace_id=? AND (t.project_id IS NULL OR t.project_id=?))",Boolean.class,run,conversation,ws,ws,project));
    }
    @Override public List<UUID> conversationProposals(UUID conversation,int offset,int limit) {
        return jdbc.queryForList("SELECT proposal_id FROM writing_v2.confirmation_proposal WHERE conversation_id=? ORDER BY created_at DESC,proposal_id LIMIT ? OFFSET ?",UUID.class,conversation,limit,offset);
    }
    @Override public List<UUID> approvedRewriteContinuations() {
        return jdbc.queryForList("""
            SELECT p.proposal_id FROM writing_v2.confirmation_proposal p
            WHERE p.proposal_type='REOPEN_ARTICLE' AND p.status='APPROVED'
              AND jsonb_exists(p.target_snapshot,'continuation') AND jsonb_exists(p.target_snapshot->'continuation','chapterKey') AND p.conversation_id IS NOT NULL
              AND NOT EXISTS(SELECT 1 FROM writing_v2.action_ledger a WHERE a.project_id=p.project_id
                  AND a.client_action_id='resume-rewrite:'||p.proposal_id::text)
            ORDER BY p.created_at LIMIT 20
            """,UUID.class);
    }
    @Override public void decideProposal(UUID id,String status,UUID action,JsonNode receipt) { jdbc.update("UPDATE writing_v2.confirmation_proposal SET status=?,decision_action_id=?,receipt_json=?::jsonb WHERE proposal_id=?",status,action,receipt==null?null:write(receipt),id); }
    private static UUID uuid(Map<String,Object> r,String k) { return (UUID)r.get(k); }
    private static long number(Map<String,Object> r,String k) { return r.get(k)==null?0:((Number)r.get(k)).longValue(); }
    private static JsonNode json(Map<String,Object> r,String k) { return r.get(k)==null?null:parse(r.get(k).toString()); }
    private static Instant instant(Map<String,Object> r,String k) { return r.get(k)==null?null:((Timestamp)r.get(k)).toInstant(); }
}
