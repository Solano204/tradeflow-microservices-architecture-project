package io.tradeflow.identity.entity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
public record RegisterAdminRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(min = 2, max = 120)
        String fullName,

        @Pattern(regexp = "^\\+?[0-9\\s\\-]{7,20}$", message = "Invalid phone format")
        String phone,

        String department  // e.g., "COMPLIANCE", "OPERATIONS", "SUPPORT"
) {}