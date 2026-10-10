package com.writingagent.v2.runtime;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Offline only: explicit run ids and stopped owner pid; no public mutation endpoint. */
@Component
@ConditionalOnProperty(name="writing.recovery.confirm-stopped", havingValue="true")
public class StoppedRunRecoveryCommand implements ApplicationRunner {
    private final Environment environment;
    private final StoppedRunReconciler reconciler;
    public StoppedRunRecoveryCommand(Environment environment, StoppedRunReconciler reconciler) {
        this.environment = environment; this.reconciler = reconciler;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if (!"none".equals(environment.getProperty("spring.main.web-application-type")))
            throw new RunContractException("OFFLINE_RECOVERY_REQUIRED");
        int port = Integer.parseInt(environment.getProperty("writing.recovery.stopped-port", "8088"));
        try (var socket = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            long pid = Long.parseLong(environment.getRequiredProperty("writing.recovery.stopped-pid"));
            String roots = environment.getRequiredProperty("writing.recovery.root-run-ids");
            for (String root : roots.split(",")) {
                var result = reconciler.reconcile(UUID.fromString(root.trim()), pid);
                System.out.println("RECOVERY run="+result.runId()+" status="+result.status());
            }
        }
    }
}
