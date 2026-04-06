package io.tradeflow.identity.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterAdminResponse(
        String adminId,
        String email,
        String fullName,
        String status,
        Instant createdAt
) {}