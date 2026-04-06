package io.tradeflow.payment.service;

public class StripeCardDeclinedException extends RuntimeException {
    private final String declineCode;
    public StripeCardDeclinedException(String message, String declineCode) {
        super(message);
        this.declineCode = declineCode;
    }
    public String getDeclineCode() { return declineCode; }
}
