package io.tradeflow.identity.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kubernetes probes:
 *   /actuator/health/startup   — Startup probe
 *   /actuator/health/liveness  — Liveness probe
 *   /actuator/health/readiness — Readiness probe (checks PG + Redis + MongoDB)
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final MongoTemplate mongoTemplate;

    /**
     * Deep readiness check — verifies all three data stores are reachable.
     * If any fails, this Pod is removed from the K8s load balancer.
     */
    @GetMapping("/health/ready")
    public Map<String, Object> readiness() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean allUp = true;

        // PostgreSQL
        try (Connection conn = dataSource.getConnection()) {
            conn.isValid(2);
            status.put("postgresql", "UP");
        } catch (Exception e) {
            status.put("postgresql", "DOWN: " + e.getMessage());
            allUp = false;
        }

        // Redis
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            status.put("redis", "UP");
        } catch (Exception e) {
            status.put("redis", "DOWN: " + e.getMessage());
            allUp = false;
        }

        // MongoDB
        try {
            mongoTemplate.getDb().runCommand(
                    new org.bson.Document("ping", 1)
            );
            status.put("mongodb", "UP");
        } catch (Exception e) {
            status.put("mongodb", "DOWN: " + e.getMessage());
            allUp = false;
        }

        status.put("status", allUp ? "UP" : "DEGRADED");

        if (!allUp) {
            throw new RuntimeException("Readiness check failed: " + status);
        }

        return status;
    }
}
