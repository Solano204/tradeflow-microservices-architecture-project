package io.tradeflow.payment.service;

public class StripeInvalidRequestException extends RuntimeException {
    public StripeInvalidRequestException(String message) { super(message); }
}
