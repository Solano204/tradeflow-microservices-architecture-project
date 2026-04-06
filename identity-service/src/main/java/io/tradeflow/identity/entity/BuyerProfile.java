package io.tradeflow.identity.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MongoDB read model for buyer profiles.
 * This is the CQRS read side — denormalized for single-fetch at checkout.
 * Rebuilt from Kafka events (buyer.registered, buyer.profile.updated).
 */
@Document(collection = "buyer_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BuyerProfile {

    @Id
    private String id;

    @Indexed(unique = true)
    private String buyerId;

    @Indexed(unique = true)
    private String email;

    private String name;

    // Denormalized address — no join needed at checkout
    private Map<String, Object> defaultAddress;

    // Saved payment method tokens (Stripe) — all in one document
    @Builder.Default
    private List<Map<String, Object>> savedPaymentMethods = new ArrayList<>();

    private String locale;

    private String status;

    private Instant createdAt;

    private Instant updatedAt;
}
