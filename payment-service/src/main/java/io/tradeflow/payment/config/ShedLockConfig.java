package io.tradeflow.payment.config;

import com.mongodb.client.MongoClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.mongo.MongoLockProvider;

@ApplicationScoped
public class ShedLockConfig {

    @Inject
    MongoClient mongoClient;

    @Produces
    @ApplicationScoped
    LockProvider lockProvider() {
        return new MongoLockProvider(mongoClient.getDatabase("payment_audit"));
    }

    @Produces
    @ApplicationScoped
    LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }
}