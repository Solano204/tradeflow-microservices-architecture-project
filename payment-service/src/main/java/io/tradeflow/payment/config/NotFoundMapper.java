package io.tradeflow.payment.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;

import io.tradeflow.payment.service.*;
import io.tradeflow.payment.dto.PaymentDtos.ErrorResponse;

// ─────────────────────────────────────────────────────────────────────────────
// SHED LOCK — distributed lock for dunning scheduler
// ─────────────────────────────────────────────────────────────────────────────


// ─────────────────────────────────────────────────────────────────────────────
// GLOBAL EXCEPTION MAPPER — Quarkus JAX-RS exception handling
// ─────────────────────────────────────────────────────────────────────────────

@Provider
@Slf4j
public class NotFoundMapper implements ExceptionMapper<NotFoundException> {
    @Override
    public Response toResponse(NotFoundException e) {
        return Response.status(404)
                .entity(ErrorResponse.of("NOT_FOUND", e.getMessage(), 404))
                .build();
    }
}

