package io.tradeflow.analytics.config;

import com.mongodb.client.MongoClient;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.mongo.MongoLockProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// ===== SHEDLOCK =====
@Configuration
public class ShedLockConfig {

    @Value("${spring.data.mongodb.database:tradeflow_analytics}")
    private String mongoDatabase;

    @Bean
    public LockProvider lockProvider(MongoClient mongoClient) {
        return new MongoLockProvider(mongoClient.getDatabase(mongoDatabase));
    }
}
