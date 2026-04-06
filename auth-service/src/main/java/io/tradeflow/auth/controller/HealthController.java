package io.tradeflow.auth.controller;

import io.quarkus.redis.datasource.RedisDataSource;
import io.tradeflow.auth.entity.AuthUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.health.*;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Health checks for Kubernetes probes.
 *
 * Kubernetes probes:
 * - Startup Probe:   GET /actuator/health/startup  (used once at Pod startup)
 * - Liveness Probe:  GET /actuator/health/live     (if fails: K8s restarts Pod)
 * - Readiness Probe: GET /actuator/health/readiness (if fails: Pod removed from LB)
 *
 * Readiness checks PostgreSQL + Redis.
 * If DB is unreachable: Pod is removed from load balancer immediately.
 * No login attempts are sent to a Pod that can't read credentials.
 *
 * GraalVM native starts in ~8ms. The startup probe prevents K8s from
 * considering the Pod unhealthy during the very brief initialization window.
 */
@Liveness
@Readiness
@ApplicationScoped
public class HealthController implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(HealthController.class);

    @Inject
    RedisDataSource redis;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("auth-service");
    }
}

/**
 * Readiness check — verifies PostgreSQL and Redis connectivity.
 */
@ApplicationScoped
@Readiness
class ReadinessCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(ReadinessCheck.class);

    @Inject
    RedisDataSource redis;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("auth-service-readiness");

        // Check PostgreSQL
        try {
            long userCount = AuthUser.count();
            builder.withData("postgresql", "UP").withData("user_count", userCount);
        } catch (Exception e) {
            LOG.error("PostgreSQL health check failed", e);
            return builder
                    .withData("postgresql", "DOWN: " + e.getMessage())
                    .down()
                    .build();
        }

        // Check Redis
        try {
            redis.value(String.class).set("health:check", "ok");
            redis.key().del("health:check");
            builder.withData("redis", "UP");
        } catch (Exception e) {
            LOG.error("Redis health check failed", e);
            return builder
                    .withData("redis", "DOWN: " + e.getMessage())
                    .down()
                    .build();
        }

        return builder.up().build();
    }
}
