package io.tradeflow.payment.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

import java.time.Duration;
import java.time.Instant;

// ─────────────────────────────────────────────────────────────────────────────
// DUNNING SCHEDULER — fires at 2 AM daily
// ShedLock ensures only ONE of N Payment Service Pods runs the tick per cycle.
// ─────────────────────────────────────────────────────────────────────────────
@ApplicationScoped
@Slf4j
public class DunningScheduler {

    @Inject
    PaymentService paymentService;
    @Inject
    LockingTaskExecutor lockingTaskExecutor;

    @Scheduled(cron = "0 0 2 * * ?")
    void tick() {
        LockConfiguration lockConfig = new LockConfiguration(
                Instant.now(),
                "dunning-tick",
                Duration.ofMinutes(55),
                Duration.ofMinutes(50)
        );

        // Cast lambda to Task explicitly — resolves the ambiguous overload
        LockingTaskExecutor.Task task = () -> {
            LockAssert.assertLocked();
            log.info("Dunning tick started");
            paymentService.dunningTick()
                    .subscribe().with(
                            __ -> log.info("Dunning tick completed"),
                            err -> log.error("Dunning tick failed: {}", err.getMessage()));
        };

        try {
            lockingTaskExecutor.executeWithLock(task, lockConfig);
        } catch (Exception e) {
            // LockException is not a public class in this ShedLock version
            // All lock failures come as generic Exception with "not locked" message
            if (e.getMessage() != null && e.getMessage().contains("lock")) {
                log.debug("Dunning tick skipped — another pod holds the lock");
            } else {
                log.error("Dunning scheduler error: {}", e.getMessage());
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
