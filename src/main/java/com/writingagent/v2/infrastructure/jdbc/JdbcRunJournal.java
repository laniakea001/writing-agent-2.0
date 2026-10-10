package com.writingagent.v2.infrastructure.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.domain.RunStatus;
import com.writingagent.v2.runtime.RunContractException;
import com.writingagent.v2.runtime.RunJournal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Sole writer of v2 events. Root lock serializes append/child registration/terminal sealing. */
@Repository
public class JdbcRunJournal implements RunJournal {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final JdbcTemplate jdbc;

    public JdbcRunJournal(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override @Transactional
    public Run create(UUID taskId, UUID actionId, UUID parentRunId, String parentToolCallId) {
        UUID id = UUID.randomUUID();
        UUID root = id;
        if (parentRunId != null) {
            Run parent = lockOpen(parentRunId);
            root = parent.rootRunId();
            // Children must stay in the same trusted project/conversation scope.
            Integer matches = jdbc.queryForObject("""
                SELECT COUNT(*)::int FROM writing_v2.writing_task t
                JOIN writing_v2.agent_run p ON p.run_id=?
                JOIN writing_v2.writing_task pt ON pt.task_id=p.task_id
                WHERE t.task_id=? AND t.workspace_id=pt.workspace_id
                  AND (pt.project_id IS NULL OR t.project_id=pt.project_id)
                  AND t.conversation_id=pt.conversation_id
                """, Integer.class, parentRunId, taskId);
            if (!Integer.valueOf(1).equals(matches)) throw new RunContractException("RUN_SCOPE_MISMATCH");
        }
        Integer bound = jdbc.queryForObject("""
            SELECT COUNT(*)::int FROM writing_v2.writing_task t
            JOIN writing_v2.action_ledger a ON a.action_id=?
            WHERE t.task_id=? AND a.project_id IS NOT DISTINCT FROM t.project_id
              AND a.workspace_id=t.workspace_id
              AND (a.conversation_id IS NULL OR a.conversation_id=t.conversation_id)
            """, Integer.class, actionId, taskId);
        if (!Integer.valueOf(1).equals(bound)) throw new RunContractException("RUN_SCOPE_MISMATCH");
        jdbc.update("""
            INSERT INTO writing_v2.agent_run(run_id, task_id, action_id, status,
              protocol_version, root_run_id, parent_run_id, parent_tool_call_id, event_seq_counter)
            VALUES(?,?,?,'RUNNING',2,?,?,?,0)
            """, id, taskId, actionId, root, parentRunId, parentToolCallId);
        return get(id);
    }

    @Override @Transactional
    public long append(UUID runId, Draft event) {
        validate(event);
        Run source = lock(runId);
        var existing = jdbc.query("""
            SELECT seq, raw_event_type, output_type, tool_call_id, invocation_id,
                   publish_required, safe_payload::text AS payload
            FROM writing_v2.run_event WHERE run_id=? AND producer_event_key=?
            """, (rs,n) -> new Existing(rs.getLong("seq"), rs.getString("raw_event_type"),
                rs.getString("output_type"), rs.getString("tool_call_id"),
                (UUID)rs.getObject("invocation_id"), rs.getBoolean("publish_required"),
                readPayload(rs.getString("payload"))), runId, event.producerEventKey());
        if (!existing.isEmpty()) {
            Existing prior = existing.get(0);
            if (!prior.same(event)) throw new RunContractException("EVENT_KEY_REUSED");
            return prior.seq();
        }
        requireOpen(source);
        return insert(source, event);
    }

    private record Existing(long seq, String raw, String output, String tool, UUID invocation,
                            boolean publish, Map<String,Object> payload) {
        boolean same(Draft e) {
            return Objects.equals(raw,e.rawEventType()) && Objects.equals(output,e.outputType())
                && Objects.equals(tool,e.toolCallId()) && Objects.equals(invocation,e.invocationId())
                && publish==e.publishRequired() && payload.equals(readPayload(json(e.safePayload())));
        }
    }

    private long insert(Run source, Draft event) {
        Long seq = jdbc.queryForObject("""
            UPDATE writing_v2.agent_run SET event_seq_counter=event_seq_counter+1, updated_at=NOW()
            WHERE run_id=? RETURNING event_seq_counter
            """, Long.class, source.runId());
        jdbc.update("""
            INSERT INTO writing_v2.run_event(event_id,run_id,seq,event_type,tool_call_id,
              correlation_id,summary,producer_event_key,raw_event_type,output_type,
              schema_version,publish_required,safe_payload,invocation_id)
            VALUES(?,?,?,?,?,?,'',?,?,?,2,?,?::jsonb,?)
            """, UUID.randomUUID(), source.runId(), seq, event.outputType(), event.toolCallId(),
            event.producerEventKey(), event.producerEventKey(), event.rawEventType(), event.outputType(),
            event.publishRequired(), json(event.safePayload()), event.invocationId());
        return Objects.requireNonNull(seq);
    }

    @Override
    public void guardBusinessCommit(UUID runId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new RunContractException("BUSINESS_COMMIT_REQUIRES_TRANSACTION");
        lockOpen(runId);
    }

    @Override @Transactional
    public Run settle(UUID runId, RunStatus terminalStatus, int modelRequests,
                      String outcomeCode, boolean resultMayExist) {
        if (terminalStatus != RunStatus.SUCCEEDED && terminalStatus != RunStatus.FAILED
                && terminalStatus != RunStatus.UNKNOWN) throw new RunContractException("INVALID_TERMINAL_STATUS");
        Run run = lock(runId);
        if (run.terminal()) return run; // stable receipt, never another terminal event
        requireOpen(run);
        boolean root = run.runId().equals(run.rootRunId());
        List<Run> descendants = root ? tree(runId) : List.of();
        boolean live = descendants.stream().anyMatch(r -> !r.runId().equals(runId) && !r.terminal());
        if (live && terminalStatus == RunStatus.SUCCEEDED)
            throw new RunContractException("CHILD_RUN_NOT_TERMINAL");
        if (live) {
            // Unknown children are sealed before the root, in the same root-locked transaction.
            for (Run child : descendants) {
                if (!child.runId().equals(runId) && !child.terminal()) {
                    Run lockedChild = lock(child.runId());
                    Integer recordedModels = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM writing_v2.run_event WHERE run_id=? AND output_type='MODEL_STARTED'",
                            Integer.class, child.runId());
                    finish(lockedChild, RunStatus.UNKNOWN, recordedModels == null ? 0 : recordedModels,
                            "ROOT_EXECUTION_INTERRUPTED", true, false);
                }
            }
            terminalStatus = RunStatus.UNKNOWN;
            resultMayExist = true;
        }
        return finish(run, terminalStatus, modelRequests, outcomeCode, resultMayExist, root);
    }

    private Run finish(Run run, RunStatus status, int models, String code,
                       boolean mayExist, boolean root) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("status", status.name());
        payload.put("resultMayExist", mayExist);
        payload.put("errorCode", code);
        payload.put("modelRequestCount", models);
        long seq = insert(run, new Draft("platform:terminal", "PLATFORM_SETTLE", "RUN_TERMINAL",
                null, null, true, payload));
        Map<UUID,Long> manifest = new LinkedHashMap<>();
        if (root) {
            for (Run descendant : tree(run.runId())) {
                if (!descendant.runId().equals(run.runId())) {
                    if (!descendant.terminal()) throw new RunContractException("CHILD_RUN_NOT_TERMINAL");
                    manifest.put(descendant.runId(), descendant.terminalSequence());
                }
            }
            manifest.put(run.runId(), seq);
        }
        jdbc.update("""
            UPDATE writing_v2.agent_run SET status=?,model_request_count=?,outcome_code=?,
              terminal_sequence=?,terminal_manifest=?::jsonb,finished_at=NOW(),updated_at=NOW()
            WHERE run_id=? AND terminal_sequence IS NULL
            """, status.name(), models, code, seq, root ? json(manifest) : null, run.runId());
        return get(run.runId());
    }

    /** First read identity, then lock root before source. Identity is immutable. */
    private Run lock(UUID id) {
        Run source = get(id);
        if (source.protocolVersion() != 2) throw new RunContractException("LEGACY_RUN_READ_ONLY");
        jdbc.queryForObject("SELECT run_id FROM writing_v2.agent_run WHERE run_id=? FOR UPDATE",
                UUID.class, source.rootRunId());
        if (!id.equals(source.rootRunId())) {
            jdbc.queryForObject("SELECT run_id FROM writing_v2.agent_run WHERE run_id=? FOR UPDATE", UUID.class, id);
        }
        return get(id);
    }

    private Run lockOpen(UUID id) { Run run=lock(id); requireOpen(run); return run; }

    private void requireOpen(Run run) {
        if (run.terminal() || get(run.rootRunId()).terminal())
            throw new RunContractException("RUN_ALREADY_TERMINAL");
    }

    @Override
    public Run get(UUID id) {
        var rows = jdbc.query("SELECT * FROM writing_v2.agent_run WHERE run_id=?", this::readRun, id);
        if (rows.isEmpty()) throw new RunContractException("RUN_NOT_FOUND");
        return rows.get(0);
    }

    @Override
    public Scope scope(UUID runId) {
        var rows = jdbc.query("""
            SELECT t.workspace_id,t.project_id,t.conversation_id,a.action_id,a.client_action_id,
                   w.owner_scope,a.operation
            FROM writing_v2.agent_run r JOIN writing_v2.writing_task t ON t.task_id=r.task_id
            JOIN writing_v2.action_ledger a ON a.action_id=r.action_id
            JOIN writing_v2.workspace w ON w.workspace_id=t.workspace_id WHERE r.run_id=?
            """, (rs,n) -> new Scope((UUID)rs.getObject("workspace_id"),
                (UUID)rs.getObject("project_id"),(UUID)rs.getObject("conversation_id"),
                (UUID)rs.getObject("action_id"),rs.getString("client_action_id"),
                rs.getString("owner_scope"),rs.getString("operation")),runId);
        if (rows.isEmpty()) throw new RunContractException("RUN_NOT_FOUND");
        return rows.get(0);
    }

    @Override
    public List<Run> tree(UUID root) {
        Run run=get(root);
        if (!root.equals(run.rootRunId())) throw new RunContractException("ROOT_RUN_REQUIRED");
        return jdbc.query("SELECT * FROM writing_v2.agent_run WHERE root_run_id=? ORDER BY created_at,run_id",
                this::readRun, root);
    }

    @Override
    public List<Event> page(UUID runId, long afterSequence, int limit) {
        Run run=get(runId);
        if (run.protocolVersion()!=2) throw new RunContractException("LEGACY_RUN_READ_ONLY");
        if (afterSequence<0 || afterSequence>run.latestSequence())
            throw new RunContractException("INVALID_EVENT_CURSOR");
        if (limit<1 || limit>256) throw new RunContractException("INVALID_PAGE_SIZE");
        List<Event> rows=jdbc.query("""
            SELECT * FROM writing_v2.run_event WHERE run_id=? AND seq>? ORDER BY seq LIMIT ?
            """, (rs,n) -> new Event((UUID)rs.getObject("event_id"), runId, run.rootRunId(),
                run.parentRunId(), run.parentToolCallId(), rs.getLong("seq"),
                rs.getString("producer_event_key"),rs.getString("raw_event_type"),
                rs.getString("output_type"),rs.getString("tool_call_id"),
                (UUID)rs.getObject("invocation_id"),rs.getBoolean("publish_required"),
                readPayload(rs.getString("safe_payload")),rs.getTimestamp("created_at").toInstant()),
                runId,afterSequence,limit);
        long expected=afterSequence+1;
        for (Event event:rows) if (event.sequence()!=expected++) throw new RunContractException("EVENT_GAP");
        if (rows.size()<limit && expected<=run.latestSequence()) throw new RunContractException("EVENT_GAP");
        return rows;
    }

    private Run readRun(ResultSet rs,int index) throws SQLException {
        Map<UUID,Long> manifest=new LinkedHashMap<>();
        String text=rs.getString("terminal_manifest");
        if (text!=null) readPayload(text).forEach((k,v) -> manifest.put(UUID.fromString(k),((Number)v).longValue()));
        return new Run((UUID)rs.getObject("run_id"),(UUID)rs.getObject("root_run_id"),
                (UUID)rs.getObject("parent_run_id"),rs.getString("parent_tool_call_id"),
                rs.getInt("protocol_version"),RunStatus.valueOf(rs.getString("status")),
                rs.getLong("event_seq_counter"),(Long)rs.getObject("terminal_sequence"), Map.copyOf(manifest));
    }

    private static void validate(Draft event) {
        if (event==null || event.producerEventKey()==null || event.producerEventKey().isBlank()
                || event.rawEventType()==null || event.outputType()==null || event.safePayload()==null)
            throw new RunContractException("INVALID_EVENT_ENVELOPE");
        if (event.outputType().equals("RUN_TERMINAL")) throw new RunContractException("TERMINAL_REQUIRES_SETTLE");
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new RunContractException("INVALID_EVENT_PAYLOAD"); }
    }

    private static Map<String,Object> readPayload(String value) {
        try { return JSON.readValue(value,new TypeReference<Map<String,Object>>() {}); }
        catch (Exception e) { throw new RunContractException("INVALID_STORED_EVENT"); }
    }
}
