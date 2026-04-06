package io.tradeflow.fraud.service;

import io.tradeflow.fraud.dto.FraudDtos;
import io.tradeflow.fraud.entity.FraudRule;
import io.tradeflow.fraud.repository.FraudRuleRepository;
import io.tradeflow.fraud.rules.RuleCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuleManagementService {

    private final FraudRuleRepository ruleRepo;
    private final RuleCache ruleCache;

    public FraudRule createRule(FraudDtos.CreateRuleRequest req) {
        FraudRule rule = FraudRule.builder()
                .id(UUID.randomUUID().toString())
                .ruleName(req.ruleName())
                .ruleType(req.ruleType())
                .description(req.description())
                .condition(convertCondition(req.condition()))
                .priority(req.priority())
                .active(true)
                .createdBy(req.createdBy())
                .justification(req.justification())
                .expiresAt(req.expiresAt())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        FraudRule saved = ruleRepo.save(rule);
        // Immediate push to in-memory cache — zero-latency enforcement
        ruleCache.addRule(saved);

        log.info("Rule created: name={}, type={}, priority={}", req.ruleName(), req.ruleType(), req.priority());
        return saved;
    }

    public FraudRule updateRule(String ruleId, FraudDtos.UpdateRuleRequest req) {
        FraudRule rule = ruleRepo.findById(ruleId)
                .orElseThrow(() -> new NotFoundException("Rule not found: " + ruleId));

        if (req.condition()   != null) rule.setCondition(convertCondition(req.condition()));
        if (req.priority()    != null) rule.setPriority(req.priority());
        if (req.description() != null) rule.setDescription(req.description());
        if (req.expiresAt()   != null) rule.setExpiresAt(req.expiresAt());
        rule.setUpdatedBy(req.updatedBy());
        rule.setChangeReason(req.changeReason());
        rule.setUpdatedAt(Instant.now());

        FraudRule saved = ruleRepo.save(rule);
        ruleCache.refreshRules(); // refresh full cache on update
        log.info("Rule updated: id={}, updatedBy={}", ruleId, req.updatedBy());
        return saved;
    }

    public List<FraudRule> listActiveRules() {
        return ruleRepo.findByActiveTrueOrderByPriorityAsc();
    }

    public void deactivateRule(String ruleId, FraudDtos.DeactivateRuleRequest req) {
        FraudRule rule = ruleRepo.findById(ruleId)
                .orElseThrow(() -> new NotFoundException("Rule not found: " + ruleId));

        rule.setActive(false);
        rule.setUpdatedBy(req != null ? req.deactivatedBy() : null);
        rule.setChangeReason(req != null ? req.reason() : "deactivated");
        rule.setUpdatedAt(Instant.now());

        ruleRepo.save(rule);
        // Immediate removal from in-memory cache
        ruleCache.removeRule(ruleId);

        log.info("Rule deactivated: id={}, by={}", ruleId, req != null ? req.deactivatedBy() : "admin");
    }

    private Map<String, Object> convertCondition(FraudDtos.RuleConditionDto dto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field",    dto.field());
        m.put("operator", dto.operator());
        if (dto.value()     != null) m.put("value",      dto.value());
        if (dto.valueList() != null) m.put("valueList",  dto.valueList());
        if (dto.redisKey()  != null) m.put("redisKey",   dto.redisKey());
        if (dto.pattern()   != null) m.put("pattern",    dto.pattern());
        if (dto.exceptions()!= null) m.put("exceptions", dto.exceptions());
        return m;
    }
}
