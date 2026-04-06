package io.tradeflow.identity.config;

import brave.Span;
import brave.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Manual tracing for Redis operations.
 * Sleuth auto-instruments HTTP, JDBC, Kafka — but NOT Redis.
 * This aspect wraps RedisService methods to create child spans.
 */
@Aspect
@Component
public class TracingConfig {

    private final Tracer tracer;

    public TracingConfig(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Wrap all RedisService method calls in a span.
     * The span will be a child of the current HTTP/Kafka span.
     */
    @Around("execution(* io.tradeflow.identity.service.RedisService.*(..))")
    public Object traceRedis(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        Span span = tracer.nextSpan().name("redis." + methodName).start();

        try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
            span.tag("component", "redis");
            span.tag("method", methodName);
            return joinPoint.proceed();
        } catch (Throwable ex) {
            span.tag("error", ex.getMessage());
            throw ex;
        } finally {
            span.finish();
        }
    }
}