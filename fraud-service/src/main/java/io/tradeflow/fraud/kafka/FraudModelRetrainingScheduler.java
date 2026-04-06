package io.tradeflow.fraud.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class FraudModelRetrainingScheduler {

    private final Job fraudModelRetrainingJob;
    private final org.springframework.batch.core.launch.JobLauncher jobLauncher;

    @Scheduled(cron = "0 0 3 * * SUN")  // Sunday 3 AM — low traffic window
    public void runWeeklyRetraining() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("startTime", Instant.now().toEpochMilli())
                    .toJobParameters();
            log.info("Starting weekly fraud model retraining job");
            JobExecution execution = jobLauncher.run(fraudModelRetrainingJob, params);
            log.info("Retraining job completed: status={}", execution.getStatus());
        } catch (Exception e) {
            log.error("Retraining job failed: {}", e.getMessage(), e);
        }
    }
}
