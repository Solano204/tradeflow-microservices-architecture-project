package io.tradeflow.order.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class BusinessValidationException extends RuntimeException {
    public BusinessValidationException(String message) { super(message); }
}
