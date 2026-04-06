package io.tradeflow.identity.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminProfileResponse(
        String adminId,
        String email,
        String fullName,
        String phone,
        String department,
        String status,
        Instant createdAt,
        Instant lastLogin
) {}