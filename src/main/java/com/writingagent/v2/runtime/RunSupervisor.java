package com.writingagent.v2.runtime;

import com.writingagent.v2.domain.RunStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The supervisor seals runs after all artifact/receipt writes, inside the same commit. */
@Service
public class RunSupervisor {
    private final RunJournal journal;
    public RunSupervisor(RunJournal journal) { this.journal = journal; }

    public void requestSettlement(UUID runId, RunStatus status, int models) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new RunContractException("SETTLEMENT_TRANSACTION_REQUIRED");
        if (status != RunStatus.SUCCEEDED && status != RunStatus.FAILED && status != RunStatus.UNKNOWN)
            throw new RunContractException("TERMINAL_STATUS_REQUIRED");
        journal.guardBusinessCommit(runId);
        // Not afterCommit: failure here rolls back the artifact and its receipt as well.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                if (readOnly) throw new RunContractException("READ_ONLY_SETTLEMENT");
                journal.settle(runId, status, models, status.name(),
                        status != RunStatus.FAILED || hasSavedArtifact(runId));
            }
        });
    }

    private boolean hasSavedArtifact(UUID runId) {
        for (var source : journal.tree(journal.get(runId).rootRunId())) {
            // For a child settlement, only its own artifacts belong to this result.
            if (!runId.equals(source.rootRunId()) && !runId.equals(source.runId())) continue;
            long cursor=0;
            while (true) {
                var page=journal.page(source.runId(),cursor,256);
                if (page.stream().anyMatch(e->"ARTIFACT_SAVED".equals(e.outputType()))) return true;
                if (page.size()<256) break;
                cursor=page.get(page.size()-1).sequence();
            }
        }
        return false;
    }
}
