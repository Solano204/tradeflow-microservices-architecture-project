package io.tradeflow.fraud.kafka;

import io.tradeflow.fraud.entity.FraudFeedback;
import io.tradeflow.fraud.entity.FraudVector;
import io.tradeflow.fraud.repository.FraudFeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class FraudModelRetrainingJobConfig {

    private final FraudFeedbackRepository feedbackRepo;
    private final MongoTemplate mongoTemplate;

    @Value("${fraud.model.version:v2024-W01}")
    private String currentModelVersion;

    @Bean
    public Job fraudModelRetrainingJob(JobRepository jobRepository,
                                       Step loadFeedbackStep,
                                       Step promoteToClusterStep,
                                       Step updateModelVersionStep) {
        return new JobBuilder("fraudModelRetraining", jobRepository)
                .start(loadFeedbackStep)
                .next(promoteToClusterStep)
                .next(updateModelVersionStep)
                .build();
    }

    @Bean
    public Step loadFeedbackStep(JobRepository jobRepository,
                                  PlatformTransactionManager txManager) {
        return new StepBuilder("loadNewFeedback", jobRepository)
                .<FraudFeedback, FraudFeedback>chunk(100, txManager)
                .reader(newFeedbackReader())
                .processor(item -> item)  // pass-through
                .writer(items -> log.info("Loaded {} new fraud feedback items", items.size()))
                .build();
    }

    @Bean
    public Step promoteToClusterStep(JobRepository jobRepository,
                                      PlatformTransactionManager txManager) {
        return new StepBuilder("promoteToFraudCluster", jobRepository)
                .<FraudFeedback, FraudFeedback>chunk(50, txManager)
                .reader(newFeedbackReader())
                .processor(feedback -> {
                    // Update fraud_vectors: label LEGITIMATE → FRAUD
                    mongoTemplate.updateFirst(
                            Query.query(Criteria.where("order_id").is(feedback.getOrderId())),
                            Update.update("label", "FRAUD")
                                    .set("fraud_type", feedback.getConfirmedFraudType())
                                    .set("confirmed_at", feedback.getConfirmedAt()),
                            FraudVector.class);
                    log.debug("Promoted to FRAUD cluster: orderId={}", feedback.getOrderId());
                    return feedback;
                })
                .writer(items -> {})
                .build();
    }

    @Bean
    public Step updateModelVersionStep(JobRepository jobRepository,
                                        PlatformTransactionManager txManager) {
        String newVersion = "v" + java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-'W'ww"));

        return new StepBuilder("updateModelVersion", jobRepository)
                .<FraudFeedback, FraudFeedback>chunk(100, txManager)
                .reader(newFeedbackReader())
                .processor(item -> item)
                .writer(items -> {
                    // Mark all processed feedback with the new model version
                    items.getItems().forEach(f -> {
                        f.setUsedInModelVersion(newVersion);
                        feedbackRepo.save(f);
                    });
                    log.info("Model version updated to {} — {} samples incorporated",
                            newVersion, items.size());
                })
                .build();
    }

    @Bean
    public ItemReader<FraudFeedback> newFeedbackReader() {
        List<FraudFeedback> items = feedbackRepo.findByUsedInModelVersionIsNull();
        return new org.springframework.batch.item.support.ListItemReader<>(items);
    }
}
