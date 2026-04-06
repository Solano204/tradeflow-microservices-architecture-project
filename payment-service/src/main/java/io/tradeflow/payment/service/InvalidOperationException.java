package io.tradeflow.payment.service;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

public class InvalidOperationException extends WebApplicationException {
    public InvalidOperationException(String message) {
        super(message, Response.Status.CONFLICT);
    }
}
