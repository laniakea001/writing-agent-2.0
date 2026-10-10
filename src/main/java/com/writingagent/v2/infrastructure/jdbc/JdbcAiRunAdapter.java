package com.writingagent.v2.infrastructure.jdbc;

import static com.writingagent.v2.domain.DocumentJsonSupport.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.writingagent.v2.application.port.AiRunPort;
import com.writingagent.v2.domain.WritingRuleException;
import com.writingagent.v2.runtime.RunJournal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAiRunAdapter implements AiRunPort {
    private final JdbcTemplate jdbc;
    private final RunJournal journal;
    public JdbcAiRunAdapter(JdbcTemplate jdbc,RunJournal journal){this.jdbc=jdbc;this.journal=journal;}
    @Override public Conversation createConversation(UUID ws){
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO writing_v2.conversation(conversation_id,workspace_id,agent_id) VALUES(?,?,'writing-main')",id,ws);
        jdbc.update("INSERT INTO writing_v2.conversation_run_admission(conversation_id) VALUES(?)",id);return conversationInfo(id);
    }
    @Override public Conversation conversationInfo(UUID id){
        var rows=jdbc.queryForList("SELECT workspace_id,project_id,active_context_version FROM writing_v2.conversation WHERE conversation_id=?",id);
        if(rows.isEmpty())throw new WritingRuleException(404,"CONVERSATION_NOT_FOUND");var r=rows.get(0);return new Conversation((UUID)r.get("workspace_id"),id,(UUID)r.get("project_id"),((Number)r.get("active_context_version")).longValue());
    }
    @Override public List<ConversationSummary> conversations(UUID ws,int offset,int limit){
        return jdbc.query("""
            SELECT c.workspace_id,c.conversation_id,c.project_id,c.created_at,
              COALESCE((SELECT left(m.body,40) FROM writing_v2.conversation_message m
                WHERE m.conversation_id=c.conversation_id AND m.role='user'
                ORDER BY m.created_at,m.message_id LIMIT 1),'新会话') AS title
            FROM writing_v2.conversation c WHERE c.workspace_id=? AND c.agent_id='writing-main'
            ORDER BY c.created_at DESC,c.conversation_id DESC LIMIT ? OFFSET ?
            """,(r,n)->new ConversationSummary(r.getObject("workspace_id",UUID.class),r.getObject("conversation_id",UUID.class),r.getObject("project_id",UUID.class),r.getString("title"),r.getTimestamp("created_at").toInstant().toString()),ws,limit,offset);
    }
    @Override public void selectProject(UUID conv,UUID project){
        UUID active=jdbc.queryForObject("SELECT active_root_run_id FROM writing_v2.conversation_run_admission WHERE conversation_id=? FOR UPDATE",UUID.class,conv);
        if(active!=null)throw new WritingRuleException(409,"CONVERSATION_BUSY");
        jdbc.update("UPDATE writing_v2.conversation SET project_id=?,active_context_version=active_context_version+1 WHERE conversation_id=?",project,conv);
    }
    @Override public UUID conversation(UUID ws,UUID project){
        var rows=jdbc.queryForList("SELECT conversation_id FROM writing_v2.conversation WHERE workspace_id=? AND project_id=? ORDER BY created_at,conversation_id LIMIT 1",ws,project);
        if(rows.isEmpty())throw new WritingRuleException(404,"CONVERSATION_NOT_FOUND");
        return (UUID)rows.get(0).get("conversation_id");
    }
    @Override public Execution register(UUID ws,UUID project,UUID conv,String key,String digest,JsonNode input){
        jdbc.queryForObject("SELECT conversation_id FROM writing_v2.conversation_run_admission WHERE conversation_id=? FOR UPDATE",UUID.class,conv);
        String scope="ASSISTANT_TURN".equals(input.path("operation").asText())?"conversation:"+conv:"project:"+project;
        var old=jdbc.queryForList("SELECT run_id,request_digest FROM writing_v2.action_ledger WHERE workspace_id=? AND scope_key=? AND client_action_id=?",ws,scope,key);
        if(!old.isEmpty()){
            if(!digest.equals(old.get(0).get("request_digest")))throw new WritingRuleException(409,"IDEMPOTENCY_KEY_REUSED");
            if(old.get(0).get("run_id")==null)throw new WritingRuleException(409,"ACTION_KIND_CONFLICT");
            return get((UUID)old.get(0).get("run_id"));
        }
        var context=conversationInfo(conv);
        if(!ws.equals(context.workspaceId())||!java.util.Objects.equals(project,context.projectId()))throw new WritingRuleException(409,"TARGET_CONTEXT_CHANGED");
        UUID active=jdbc.queryForObject("SELECT active_root_run_id FROM writing_v2.conversation_run_admission WHERE conversation_id=?",UUID.class,conv);
        if(active!=null)throw new WritingRuleException(409,"CONVERSATION_BUSY");
        UUID action=UUID.randomUUID(),task=UUID.randomUUID();
        var inserted=jdbc.queryForList("""
            INSERT INTO writing_v2.action_ledger(action_id,workspace_id,project_id,scope_key,client_action_id,request_digest,digest_version,operation,status,conversation_id,execution_request_json)
            VALUES(?,?,?,?,?,?,2,?,'CLAIMED',?,?::jsonb)
            ON CONFLICT(workspace_id,scope_key,client_action_id) DO NOTHING RETURNING action_id
            """,action,ws,project,scope,key,digest,input.path("operation").asText(),conv,write(input));
        if(inserted.isEmpty())throw new WritingRuleException(409,"IDEMPOTENCY_KEY_REUSED");
        jdbc.update("INSERT INTO writing_v2.writing_task(task_id,workspace_id,project_id,conversation_id,status) VALUES(?,?,?,?,'CREATED')",task,ws,project,conv);
        UUID run=journal.create(task,action,null,null).runId();
        jdbc.update("UPDATE writing_v2.action_ledger SET task_id=?,run_id=? WHERE action_id=?",task,run,action);
        jdbc.update("UPDATE writing_v2.conversation_run_admission SET active_root_run_id=?,updated_at=now() WHERE conversation_id=?",run,conv);
        queued(run);
        var e=get(run);return new Execution(ws,project,conv,action,task,run,key,digest,input,null,true);
    }
    @Override public void queued(UUID run){jdbc.update("UPDATE writing_v2.agent_run SET status='QUEUED' WHERE run_id=?",run);}
    @Override public void running(UUID run,UUID owner){
        jdbc.update("UPDATE writing_v2.agent_run SET status='RUNNING',execution_owner_id=?,execution_owner_pid=?,execution_started_at=now() WHERE run_id=? AND status='QUEUED'",owner,ProcessHandle.current().pid(),run);
        jdbc.update("UPDATE writing_v2.writing_task SET status='RUNNING' WHERE task_id=(SELECT task_id FROM writing_v2.agent_run WHERE run_id=?)",run);
    }
    @Override public Execution get(UUID run){
        var rows=jdbc.queryForList("SELECT a.* FROM writing_v2.action_ledger a WHERE run_id=? AND execution_request_json IS NOT NULL",run);
        if(rows.isEmpty())throw new WritingRuleException(404,"RUN_NOT_FOUND");
        var r=rows.get(0);return new Execution((UUID)r.get("workspace_id"),(UUID)r.get("project_id"),(UUID)r.get("conversation_id"),(UUID)r.get("action_id"),(UUID)r.get("task_id"),run,(String)r.get("client_action_id"),(String)r.get("request_digest"),parse(r.get("execution_request_json").toString()),r.get("receipt_json")==null?null:parse(r.get("receipt_json").toString()),false);
    }
    @Override public List<Execution> list(UUID ws){return jdbc.queryForList("SELECT r.run_id FROM writing_v2.agent_run r JOIN writing_v2.action_ledger a USING(action_id) WHERE a.workspace_id=? AND r.parent_run_id IS NULL AND a.execution_request_json IS NOT NULL ORDER BY r.created_at DESC LIMIT 100",ws).stream().map(r->get((UUID)r.get("run_id"))).toList();}
    @Override public Execution lookup(UUID project,String key){
        var rows=jdbc.queryForList("SELECT run_id FROM writing_v2.action_ledger WHERE project_id=? AND client_action_id=? AND execution_request_json IS NOT NULL",project,key);
        if(rows.isEmpty())throw new WritingRuleException(404,"ACTION_NOT_FOUND");return get((UUID)rows.get(0).get("run_id"));
    }
    @Override public Execution lookupConversation(UUID conv,String key){
        var rows=jdbc.queryForList("SELECT run_id FROM writing_v2.action_ledger WHERE conversation_id=? AND scope_key=? AND client_action_id=? AND execution_request_json IS NOT NULL",conv,"conversation:"+conv,key);
        if(rows.isEmpty())throw new WritingRuleException(404,"ACTION_NOT_FOUND");return get((UUID)rows.get(0).get("run_id"));
    }
    @Override public Execution registerChild(Execution parent,UUID project,String call,String name,JsonNode input){
        journal.guardBusinessCommit(parent.runId());
        String key="tool|"+parent.runId()+"|"+call+"|"+name;String scope="project:"+project;
        var previous=jdbc.queryForList("SELECT run_id,request_digest FROM writing_v2.action_ledger WHERE workspace_id=? AND scope_key=? AND client_action_id=?",parent.workspaceId(),scope,key);
        if(!previous.isEmpty()){if(!com.writingagent.v2.application.support.RequestDigests.sha256(write(canonical(input))).equals(previous.get(0).get("request_digest")))throw new WritingRuleException(409,"IDEMPOTENCY_KEY_REUSED");return get((UUID)previous.get(0).get("run_id"));}
        UUID action=UUID.randomUUID(),task=UUID.randomUUID();
        jdbc.update("INSERT INTO writing_v2.action_ledger(action_id,workspace_id,project_id,scope_key,client_action_id,request_digest,digest_version,operation,status,conversation_id,execution_request_json) VALUES(?,?,?,?,?,?,2,?,'CLAIMED',?,?::jsonb)",action,parent.workspaceId(),project,scope,key,com.writingagent.v2.application.support.RequestDigests.sha256(write(canonical(input))),input.path("operation").asText(),parent.conversationId(),write(input));
        jdbc.update("INSERT INTO writing_v2.writing_task(task_id,workspace_id,project_id,conversation_id,status) VALUES(?,?,?,?,'CREATED')",task,parent.workspaceId(),project,parent.conversationId());
        UUID run=journal.create(task,action,parent.runId(),call).runId();jdbc.update("UPDATE writing_v2.action_ledger SET task_id=?,run_id=? WHERE action_id=?",task,run,action);queued(run);
        return new Execution(parent.workspaceId(),project,parent.conversationId(),action,task,run,key,com.writingagent.v2.application.support.RequestDigests.sha256(write(canonical(input))),input,null,true);
    }
    @Override public void finish(Execution e,JsonNode receipt,boolean success,boolean uncertain){
        int recorded=jdbc.update("UPDATE writing_v2.action_ledger SET status=?,receipt_json=?::jsonb,result_snapshot_json=?::jsonb,result_may_exist=?,updated_at=now() WHERE action_id=? AND receipt_json IS NULL",success?"RESULT_RECORDED":uncertain?"UNKNOWN_GENERATION":"FAILED",write(receipt),write(receipt),uncertain||success,e.actionId());
        if(recorded==0)return;
        jdbc.update("UPDATE writing_v2.writing_task SET status=? WHERE task_id=?",success?"COMPLETED":uncertain?"WAITING_USER":"FAILED",e.taskId());
        if(!uncertain)jdbc.update("UPDATE writing_v2.conversation_run_admission SET active_root_run_id=null,updated_at=now() WHERE conversation_id=? AND active_root_run_id=?",e.conversationId(),e.runId());
    }
    @Override public JsonNode reconcileTerminal(Execution e,String key,JsonNode snapshot){
        // Admission first, same ordering as registration. A sealed root is the fencing
        // authority: late callbacks cannot pass guardBusinessCommit and save artifacts.
        UUID active=jdbc.queryForObject("SELECT active_root_run_id FROM writing_v2.conversation_run_admission WHERE conversation_id=? FOR UPDATE",UUID.class,e.conversationId());
        String scope="recovery:"+e.runId();
        var prior=jdbc.queryForList("SELECT receipt_json FROM writing_v2.action_ledger WHERE workspace_id=? AND scope_key=? AND client_action_id=?",e.workspaceId(),scope,key);
        if(!prior.isEmpty())return parse(prior.get(0).get("receipt_json").toString());
        jdbc.queryForObject("SELECT run_id FROM writing_v2.agent_run WHERE run_id=? FOR UPDATE",UUID.class,e.runId());
        var root=journal.get(e.runId());
        if(root.parentRunId()!=null)throw new WritingRuleException(409,"ROOT_RUN_REQUIRED");
        if(!root.terminal())throw new WritingRuleException(409,"RUN_STILL_ACTIVE");
        if(root.status()!=com.writingagent.v2.domain.RunStatus.UNKNOWN)throw new WritingRuleException(409,"RUN_NOT_UNKNOWN");
        if(journal.tree(e.runId()).stream().anyMatch(r->!r.terminal()))throw new WritingRuleException(409,"CHILD_RUN_NOT_TERMINAL");
        if(active!=null&&!active.equals(e.runId()))throw new WritingRuleException(409,"CONVERSATION_BUSY");
        jdbc.update("INSERT INTO writing_v2.action_ledger(action_id,workspace_id,project_id,scope_key,client_action_id,request_digest,digest_version,operation,status,conversation_id,receipt_json,result_snapshot_json,result_may_exist) VALUES(?,?,?,?,?,?,2,'RECONCILE_RUN','RESULT_RECORDED',?,?::jsonb,?::jsonb,false)",UUID.randomUUID(),e.workspaceId(),e.projectId(),scope,key,com.writingagent.v2.application.support.RequestDigests.sha256(e.runId().toString()),e.conversationId(),write(snapshot),write(snapshot));
        jdbc.update("UPDATE writing_v2.conversation_run_admission SET active_root_run_id=null,updated_at=now() WHERE conversation_id=? AND active_root_run_id=?",e.conversationId(),e.runId());
        return snapshot;
    }
}
