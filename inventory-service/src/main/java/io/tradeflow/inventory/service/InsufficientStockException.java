package io.tradeflow.inventory.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class InsufficientStockException extends RuntimeException {
    private final int availableQty;
    public InsufficientStockException(String message, int availableQty) {
        super(message);
        this.availableQty = availableQty;
    }
    public int getAvailableQty() { return availableQty; }
}
