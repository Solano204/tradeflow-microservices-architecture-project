package io.tradeflow.payment.service;

public class StripeNetworkException extends RuntimeException {
    public StripeNetworkException(String message, Throwable cause) { super(message, cause); }
}
