package io.tradeflow.fraud.rules;

import io.tradeflow.fraud.entity.FraudRule;
import io.tradeflow.fraud.repository.FraudRuleRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * In-memory rule cache.
 *
 * Rules are loaded from MongoDB into a volatile List every 60 seconds.
 * The volatile keyword ensures all threads always see the latest reference.
 *
 * Maximum staleness: 60 seconds. A new BLOCK rule added via POST /fraud/rules
 * is also directly pushed to this cache (zero-latency for immediate enforcement).
 *
 * Rule evaluation is O(N_rules × 1) per request — microseconds, no DB hit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RuleCache {

    private final FraudRuleRepository ruleRepo;

    /** volatile ensures all threads always see the latest reference */
    private volatile List<FraudRule> activeRules = Collections.emptyList();

    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(name = "rule-cache-refresh", lockAtMostFor = "PT50S")
    public void refreshRules() {
        try {
            List<FraudRule> fresh = ruleRepo.findByActiveTrueOrderByPriorityAsc();
            // Filter out expired rules
            List<FraudRule> valid = fresh.stream()
                    .filter(r -> !r.isExpired())
                    .toList();
            this.activeRules = Collections.unmodifiableList(valid);
            log.debug("Rule cache refreshed: {} active rules", valid.size());
        } catch (Exception e) {
            log.error("Rule cache refresh failed — using stale cache: {}", e.getMessage());
        }
    }

    public List<FraudRule> getActiveRules() {
        return activeRules;
    }

    /** Immediate push — used after POST /fraud/rules for zero-latency enforcement */
    public synchronized void addRule(FraudRule rule) {
        List<FraudRule> updated = new ArrayList<>(activeRules);
        updated.add(rule);
        updated.sort(Comparator.comparingInt(FraudRule::getPriority));
        this.activeRules = Collections.unmodifiableList(updated);
    }

    /** Immediate deactivation — used after DELETE /fraud/rules */
    public synchronized void removeRule(String ruleId) {
        this.activeRules = activeRules.stream()
                .filter(r -> !r.getId().equals(ruleId))
                .toList();
    }
}

