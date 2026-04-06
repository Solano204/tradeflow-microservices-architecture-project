package io.tradeflow.fraud.service;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import io.tradeflow.fraud.dto.FraudDtos.*;
import io.tradeflow.fraud.entity.*;
import io.tradeflow.fraud.repository.*;
import io.tradeflow.fraud.rules.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

// ─────────────────────────────────────────────────────────────────────────────
// RULE MANAGEMENT SERVICE — Endpoints 4, 5, 6, 7
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// EXCEPTIONS
// ─────────────────────────────────────────────────────────────────────────────

public class ConflictException extends ResponseStatusException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
