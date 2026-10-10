package com.writingagent.v2.runtime;

import com.writingagent.v2.application.ActionLedgerService;
import com.writingagent.v2.domain.ActionStatus;
import com.writingagent.v2.domain.RunStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operator-selected single-instance recovery, never inferred from an empty local registry. */
@Service
public class StoppedRunReconciler {
    private final RunJournal journal;
    private final ActionLedgerService actions;
    private final com.writingagent.v2.application.port.TaskRunPort runs;
    public StoppedRunReconciler(RunJournal journal, ActionLedgerService actions,
            com.writingagent.v2.application.port.TaskRunPort runs) {
        this.journal = journal; this.actions = actions; this.runs = runs;
    }

    @Transactional
    public RunJournal.Run reconcile(UUID rootId, long explicitlyConfirmedStoppedPid) {
        if (explicitlyConfirmedStoppedPid <= 0 || ProcessHandle.of(explicitlyConfirmedStoppedPid)
                .map(ProcessHandle::isAlive).orElse(false))
            throw new RunContractException("OWNER_NOT_CONFIRMED_STOPPED");
        var root = journal.get(rootId);
        if (!root.runId().equals(root.rootRunId())) throw new RunContractException("ROOT_RUN_REQUIRED");
        if (root.terminal()) return root;
        var sealed = journal.settle(rootId, RunStatus.UNKNOWN, runs.countEvents(rootId,"MODEL_STARTED"),
                "OPERATOR_CONFIRMED_OWNER_STOPPED", true);
        for (var run : journal.tree(rootId)) {
            var scope = journal.scope(run.runId());
            var action = actions.find(scope.projectId(), scope.clientActionId()).orElseThrow();
            // A completed child artifact is not undone by its parent's interrupted execution.
            if ("CLAIMED".equals(action.status()))
                actions.markResult(scope.actionId(), ActionStatus.UNKNOWN_GENERATION, null, true,
                        "OPERATOR_CONFIRMED_OWNER_STOPPED");
        }
        return sealed;
    }
}
