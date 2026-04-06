package io.tradeflow.payment.service;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

public class InvalidRefundException extends WebApplicationException {
    public InvalidRefundException(String message) {
        super(message, Response.Status.NOT_IMPLEMENTED);
    }
}
