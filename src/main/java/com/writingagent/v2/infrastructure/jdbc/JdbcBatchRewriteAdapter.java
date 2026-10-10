package com.writingagent.v2.infrastructure.jdbc;

import static com.writingagent.v2.domain.DocumentJsonSupport.*;
import com.writingagent.v2.application.port.BatchRewritePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.writingagent.v2.domain.WritingRuleException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBatchRewriteAdapter implements BatchRewritePort {
 private final JdbcTemplate jdbc;
 public JdbcBatchRewriteAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Optional<Batch> lookup(UUID p,String key){var ids=jdbc.queryForList("SELECT batch_id FROM writing_v2.batch_rewrite WHERE project_id=? AND client_action_id=?",UUID.class,p,key);return ids.isEmpty()?Optional.empty():get(ids.get(0));}
 public Optional<Batch> get(UUID id){
  var rows=jdbc.queryForList("SELECT * FROM writing_v2.batch_rewrite WHERE batch_id=?",id);if(rows.isEmpty())return Optional.empty();var r=rows.get(0);
  var items=jdbc.query("SELECT * FROM writing_v2.batch_rewrite_item WHERE batch_id=? ORDER BY ordinal",(rs,n)->new Item((UUID)rs.getObject("item_id"),rs.getInt("ordinal"),(UUID)rs.getObject("chapter_key"),rs.getInt("chapter_index"),(UUID)rs.getObject("initial_revision_id"),ItemStatus.valueOf(rs.getString("status")),(UUID)rs.getObject("generation_run_id"),(UUID)rs.getObject("proposal_id")),id);
  return Optional.of(new Batch(id,(UUID)r.get("workspace_id"),(UUID)r.get("project_id"),(UUID)r.get("conversation_id"),r.get("origin").toString(),r.get("request_digest").toString(),r.get("instruction").toString(),Status.valueOf(r.get("status").toString()),((Number)r.get("version")).longValue(),parse(r.get("expected_manifest").toString()),items,(UUID)r.get("reopen_proposal_id")));
 }
 public Optional<Batch> active(UUID p){var ids=jdbc.queryForList("SELECT batch_id FROM writing_v2.batch_rewrite WHERE project_id=? AND status NOT IN ('COMPLETED','COMPLETED_WITH_SKIPS','STOPPED')",UUID.class,p);if(ids.size()>1)throw new WritingRuleException(409,"INCONSISTENT_BATCH_BINDING");return ids.isEmpty()?Optional.empty():get(ids.get(0));}
 public boolean hasActive(UUID p){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM writing_v2.batch_rewrite WHERE project_id=? AND status NOT IN ('COMPLETED','COMPLETED_WITH_SKIPS','STOPPED'))",Boolean.class,p));}
 public void pauseChanged(UUID id){jdbc.update("UPDATE writing_v2.batch_rewrite SET status='PAUSED',version=version+1,updated_at=now() WHERE batch_id=? AND status='RUNNING'",id);}
 public boolean acceptsCandidate(UUID project,String key,UUID run){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM writing_v2.batch_rewrite b JOIN writing_v2.batch_rewrite_item i USING(batch_id) WHERE b.project_id=? AND b.status IN ('RUNNING','PAUSED') AND ?=('batch-candidate:'||i.item_id::text) AND i.status IN ('PENDING','GENERATING') AND i.generation_run_id IS NOT DISTINCT FROM ?::uuid)",Boolean.class,project,key,run));}
 public boolean ownsProposal(UUID project,UUID proposal){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM writing_v2.batch_rewrite b JOIN writing_v2.batch_rewrite_item i USING(batch_id) WHERE b.project_id=? AND i.proposal_id=? AND b.status='WAITING_CHAPTER_CONFIRMATION' AND i.status='WAITING_CONFIRMATION')",Boolean.class,project,proposal));}
 public Batch insert(UUID ws,UUID p,String key,String digest,String instruction,Status status,JsonNode manifest,List<Item> items){
  UUID id=UUID.randomUUID();jdbc.update("INSERT INTO writing_v2.batch_rewrite(batch_id,workspace_id,project_id,origin,client_action_id,request_digest,instruction,expected_manifest,status) VALUES(?,?,?,'HTTP',?,?,?,?::jsonb,?)",id,ws,p,key,digest,instruction,write(manifest),status.name());
  for(var item:items)jdbc.update("INSERT INTO writing_v2.batch_rewrite_item(item_id,batch_id,ordinal,chapter_key,chapter_index,initial_revision_id,expected_revision_id,status) VALUES(?,?,?,?,?,?,?,'PENDING')",item.itemId(),id,item.ordinal(),item.chapterKey(),item.chapterIndex(),item.initialRevisionId(),item.initialRevisionId());
  return get(id).orElseThrow();
 }
 public void bindCandidate(UUID batch,UUID item,UUID proposal,UUID run){
  int changed=jdbc.update("UPDATE writing_v2.batch_rewrite_item SET status='WAITING_CONFIRMATION',proposal_id=?,generation_run_id=?,updated_at=now() WHERE batch_id=? AND item_id=? AND status IN ('PENDING','GENERATING') AND proposal_id IS NULL",proposal,run,batch,item);
  if(changed!=1)throw new WritingRuleException(409,"BATCH_ITEM_CHANGED");
  jdbc.update("UPDATE writing_v2.batch_rewrite SET status=CASE WHEN status='PAUSED' THEN 'PAUSED' ELSE 'WAITING_CHAPTER_CONFIRMATION' END,version=version+1,updated_at=now() WHERE batch_id=?",batch);
 }
 public void guardDecision(UUID proposal){
  var rows=jdbc.queryForList("SELECT b.status AS batch_status,i.status AS item_status FROM writing_v2.batch_rewrite b JOIN writing_v2.batch_rewrite_item i USING(batch_id) WHERE i.proposal_id=? FOR UPDATE OF b,i",proposal);
  if(rows.isEmpty()){var reopen=jdbc.queryForList("SELECT status FROM writing_v2.batch_rewrite WHERE reopen_proposal_id=? FOR UPDATE",proposal);if(!reopen.isEmpty()&&!"WAITING_REOPEN".equals(reopen.get(0).get("status")))throw new WritingRuleException(409,"BATCH_NOT_ACCEPTING_DECISION");return;}
  var row=rows.get(0);if(!"WAITING_CHAPTER_CONFIRMATION".equals(row.get("batch_status"))||!"WAITING_CONFIRMATION".equals(row.get("item_status")))throw new WritingRuleException(409,"BATCH_NOT_ACCEPTING_DECISION");
 }
 public void decisionRecorded(UUID proposal,String status,JsonNode receipt,JsonNode current){
  var rows=jdbc.queryForList("SELECT batch_id,item_id,ordinal FROM writing_v2.batch_rewrite_item WHERE proposal_id=?",proposal);
  if(rows.isEmpty()){jdbc.update("UPDATE writing_v2.batch_rewrite SET status=?,expected_manifest=?::jsonb,version=version+1,updated_at=now() WHERE reopen_proposal_id=?","APPROVED".equals(status)?"RUNNING":"REJECTED".equals(status)?"STOPPED":"PAUSED",write(current),proposal);return;}
  var item=rows.get(0);UUID batch=(UUID)item.get("batch_id");
  if(!Set.of("APPROVED","REJECTED").contains(status)){
   jdbc.update("UPDATE writing_v2.batch_rewrite SET status='PAUSED',version=version+1,updated_at=now() WHERE batch_id=?",batch);return;
  }
  jdbc.update("UPDATE writing_v2.batch_rewrite_item SET status=?,decision_action_id=?,receipt_json=?::jsonb,updated_at=now() WHERE item_id=?","APPROVED".equals(status)?"APPLIED":"REJECTED",UUID.fromString(receipt.path("actionId").asText()),write(receipt),item.get("item_id"));
  int pending=jdbc.queryForObject("SELECT count(*) FROM writing_v2.batch_rewrite_item WHERE batch_id=? AND status NOT IN ('APPLIED','REJECTED')",Integer.class,batch);
  boolean skipped=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM writing_v2.batch_rewrite_item WHERE batch_id=? AND status='REJECTED')",Boolean.class,batch));
  jdbc.update("UPDATE writing_v2.batch_rewrite SET status=?,active_ordinal=?,expected_manifest=?::jsonb,version=version+1,updated_at=now() WHERE batch_id=?",pending==0?(skipped?"COMPLETED_WITH_SKIPS":"COMPLETED"):"RUNNING",((Number)item.get("ordinal")).intValue()+1,write(current),batch);
 }
 public void bindReopen(UUID batch,UUID proposal){if(jdbc.update("UPDATE writing_v2.batch_rewrite SET reopen_proposal_id=? WHERE batch_id=? AND status='WAITING_REOPEN' AND reopen_proposal_id IS NULL",proposal,batch)!=1)throw new WritingRuleException(409,"BATCH_ITEM_CHANGED");}
 public void attachConversation(UUID batch,UUID conversation){jdbc.update("UPDATE writing_v2.batch_rewrite SET conversation_id=? WHERE batch_id=? AND conversation_id IS NULL",conversation,batch);}
 public void bindAgentContext(UUID batch,UUID conversation,UUID run){jdbc.update("UPDATE writing_v2.batch_rewrite SET origin='AGENT',conversation_id=?,source_run_id=? WHERE batch_id=? AND source_run_id IS NULL",conversation,run,batch);}
 public void activate(UUID batch){jdbc.update("UPDATE writing_v2.batch_rewrite SET dispatch_activated_at=COALESCE(dispatch_activated_at,now()) WHERE batch_id=?",batch);}
 public Optional<JsonNode> controlReceipt(UUID batch,String key,String digest){var rows=jdbc.queryForList("SELECT request_digest,receipt_json FROM writing_v2.batch_control_receipt WHERE batch_id=? AND client_action_id=?",batch,key);if(rows.isEmpty())return Optional.empty();if(!digest.equals(rows.get(0).get("request_digest")))throw new WritingRuleException(409,"IDEMPOTENCY_KEY_REUSED");return Optional.of(parse(rows.get(0).get("receipt_json").toString()));}
 public void recordControl(UUID batch,String key,String digest,Status next,JsonNode receipt){jdbc.update("UPDATE writing_v2.batch_rewrite SET status=?,version=version+1,updated_at=now() WHERE batch_id=?",next.name(),batch);jdbc.update("INSERT INTO writing_v2.batch_control_receipt(batch_id,client_action_id,request_digest,receipt_json) VALUES(?,?,?,?::jsonb)",batch,key,digest,write(receipt));}
 public boolean decisionBlocked(UUID proposal){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM writing_v2.batch_rewrite b LEFT JOIN writing_v2.batch_rewrite_item i USING(batch_id) WHERE (b.reopen_proposal_id=? OR i.proposal_id=?) AND b.status IN ('PAUSED','UNKNOWN','STOPPED'))",Boolean.class,proposal,proposal));}
 public void generationFailed(UUID batch,UUID run,boolean uncertain){
  int n=jdbc.update("UPDATE writing_v2.batch_rewrite_item SET status=?,updated_at=now() WHERE batch_id=? AND generation_run_id=? AND status='GENERATING'",uncertain?"UNKNOWN":"FAILED",batch,run);
  if(n==1)jdbc.update("UPDATE writing_v2.batch_rewrite SET status=?,version=version+1,updated_at=now() WHERE batch_id=? AND status='RUNNING'",uncertain?"UNKNOWN":"PAUSED",batch);
 }
 public List<UUID> dispatchableIds(){return jdbc.queryForList("""
  SELECT b.batch_id FROM writing_v2.batch_rewrite b
  JOIN writing_v2.batch_rewrite_item i ON i.batch_id=b.batch_id AND i.ordinal=b.active_ordinal
  WHERE b.dispatch_activated_at IS NOT NULL AND b.status='RUNNING'
    AND i.status='PENDING' AND i.generation_claimed_at IS NULL AND i.generation_run_id IS NULL
  ORDER BY b.created_at LIMIT 20
  """,UUID.class);}
 public void claimGeneration(UUID batch,UUID item,UUID run){
  int changed=jdbc.update("UPDATE writing_v2.batch_rewrite_item SET status='GENERATING',generation_run_id=?,generation_owner_pid=?,generation_claimed_at=now(),updated_at=now() WHERE batch_id=? AND item_id=? AND status='PENDING' AND generation_claimed_at IS NULL AND generation_run_id IS NULL",run,ProcessHandle.current().pid(),batch,item);
  if(changed!=1)throw new WritingRuleException(409,"BATCH_ITEM_CHANGED");
 }
 public Optional<GenerationOwner> generationOwner(UUID id){return jdbc.query("SELECT batch_id,generation_run_id,generation_owner_pid FROM writing_v2.batch_rewrite_item WHERE batch_id=? AND status='GENERATING' AND generation_owner_pid IS NOT NULL",(rs,n)->new GenerationOwner((UUID)rs.getObject(1),(UUID)rs.getObject(2),rs.getLong(3)),id).stream().findFirst();}
 public List<GenerationOwner> activeGenerationOwners(){return jdbc.query("SELECT i.batch_id,i.generation_run_id,i.generation_owner_pid FROM writing_v2.batch_rewrite_item i JOIN writing_v2.batch_rewrite b USING(batch_id) WHERE i.status='GENERATING' AND i.generation_owner_pid IS NOT NULL AND b.dispatch_activated_at IS NOT NULL AND b.status IN ('RUNNING','PAUSED','STOPPED')",(rs,n)->new GenerationOwner((UUID)rs.getObject(1),(UUID)rs.getObject(2),rs.getLong(3)));}
}
