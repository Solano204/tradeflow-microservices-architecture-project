package io.tradeflow.inventory.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.GONE)
public class ReservationExpiredException extends RuntimeException {
    public ReservationExpiredException(String message) { super(message); }
}
