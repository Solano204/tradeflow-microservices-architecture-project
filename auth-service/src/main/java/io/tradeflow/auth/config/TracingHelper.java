package io.tradeflow.auth.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * TracingHelper — manual span creation for Redis and any operation
 * not auto-instrumented by Quarkus OTel.
 *
 * Usage (always try-with-resources):
 *
 *   try (RedisSpan rs = tracingHelper.startRedisSpan("SET", "rt:" + userId)) {
 *       redisClient.set(key, value);
 *   } catch (Exception e) {
 *       rs.setError(e);   // marks span ERROR before it closes
 *       throw e;
 *   }
 */
@ApplicationScoped
public class TracingHelper {

    private static final String INSTRUMENTATION_NAME = "tradeflow-auth-service";

    private Tracer tracer() {
        // Resolved lazily so OTel SDK is fully initialised before first use
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    // ----------------------------------------------------------------
    // Redis spans
    // ----------------------------------------------------------------

    public RedisSpan startRedisSpan(String operation, String key) {
        Span span = tracer().spanBuilder("redis." + operation)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.system", "redis")
                .setAttribute("db.operation", operation)
                .setAttribute("db.redis.key", key)
                .startSpan();
        Scope scope = span.makeCurrent();
        return new RedisSpan(span, scope);
    }

    // ----------------------------------------------------------------
    // Generic internal spans  (Kafka business logic, outbox, etc.)
    // ----------------------------------------------------------------

    public OtelSpan startSpan(String name) {
        Span span = tracer().spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        Scope scope = span.makeCurrent();
        return new OtelSpan(span, scope);
    }

    // ----------------------------------------------------------------
    // Reusable AutoCloseable wrapper
    // ----------------------------------------------------------------

    public static class OtelSpan implements AutoCloseable {
        protected final Span  span;
        protected final Scope scope;

        public OtelSpan(Span span, Scope scope) {
            this.span  = span;
            this.scope = scope;
        }

        public OtelSpan tag(String key, String value) {
            span.setAttribute(key, value);
            return this;
        }

        public OtelSpan tag(String key, long value) {
            span.setAttribute(key, value);
            return this;
        }

        public void setError(Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR, t.getMessage());
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }

    public static class RedisSpan extends OtelSpan {
        public RedisSpan(Span span, Scope scope) {
            super(span, scope);
        }
    }
}