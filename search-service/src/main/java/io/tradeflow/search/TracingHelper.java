package io.tradeflow.search;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * TracingHelper — utility for adding manual child spans to any operation.
 *
 * Spring Boot 3.x / Micrometer Tracing uses "Observations" instead of
 * direct Span manipulation. An Observation creates a span automatically
 * and links it to the current trace context.
 *
 * USE CASES:
 *   - Redis operations (not auto-instrumented)
 *   - External HTTP calls via RestTemplate/WebClient (auto, but you can add tags)
 *   - Business logic you want to see as a named span in Zipkin
 *   - Any blocking operation you want to measure
 *
 * USAGE:
 *   // Redis SET — appears as child span under current traceId
 *   String result = tracingHelper.observe("redis.set", "key", "rt:" + userId,
 *       () -> redisTemplate.opsForValue().set(key, value));
 *
 *   // Business operation
 *   BuyerEntity buyer = tracingHelper.observe("buyer.provision", "buyer_id", buyerId,
 *       () -> buyerRepository.save(newBuyer));
 */
@Component
public class TracingHelper {

    private final ObservationRegistry observationRegistry;

    public TracingHelper(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    /**
     * Wrap a supplier in a named child span with a key/value tag.
     * The span automatically ends when the supplier returns (or throws).
     *
     * @param name    Span name shown in Zipkin (e.g. "redis.get", "buyer.create")
     * @param tagKey  A tag key visible in Zipkin span details (e.g. "db.key")
     * @param tagValue The tag value (e.g. "rt:user-123")
     * @param supplier The operation to trace
     * @return The result of the operation
     */
    public <T> T observe(String name, String tagKey, String tagValue, Supplier<T> supplier) {
        return Observation.createNotStarted(name, observationRegistry)
                .lowCardinalityKeyValue(tagKey, tagValue)
                .observe(supplier::get);
    }

    /**
     * Wrap a void operation in a named child span.
     */
    public void observeVoid(String name, String tagKey, String tagValue, Runnable runnable) {
        Observation.createNotStarted(name, observationRegistry)
                .lowCardinalityKeyValue(tagKey, tagValue)
                .observe(runnable);
    }

    /**
     * Add a tag to the CURRENT active span (no new span created).
     * Use this inside @KafkaListener or @GetMapping methods to tag
     * the auto-created span with business context.
     */
    public void tagCurrentSpan(String key, String value) {
        Observation currentObservation = observationRegistry.getCurrentObservation();
        if (currentObservation != null) {
            currentObservation.lowCardinalityKeyValue(key, value);
        }
    }
}