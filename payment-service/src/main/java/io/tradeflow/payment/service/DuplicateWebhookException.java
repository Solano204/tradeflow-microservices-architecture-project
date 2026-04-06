package io.tradeflow.payment.service;

public class DuplicateWebhookException extends RuntimeException {
    public DuplicateWebhookException(String eventId) {
        super("Duplicate Stripe webhook: " + eventId);
    }
}
