package io.tradeflow.inventory.config;

import io.tradeflow.inventory.dto.InventoryDtos;
import io.tradeflow.inventory.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<InventoryDtos.ErrorResponse> notFound(NotFoundException e) {
        return ResponseEntity.status(404).body(InventoryDtos.ErrorResponse.of("NOT_FOUND", e.getMessage(), 404));
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<Map<String, Object>> insufficientStock(InsufficientStockException e) {
        return ResponseEntity.status(409).body(Map.of(
                "error", "INSUFFICIENT_STOCK",
                "message", e.getMessage(),
                "available", e.getAvailableQty(),
                "status", 409
        ));
    }

    @ExceptionHandler(ReservationExpiredException.class)
    ResponseEntity<InventoryDtos.ErrorResponse> reservationExpired(ReservationExpiredException e) {
        return ResponseEntity.status(410).body(InventoryDtos.ErrorResponse.of("RESERVATION_EXPIRED", e.getMessage(), 410));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<InventoryDtos.ErrorResponse> conflict(ConflictException e) {
        return ResponseEntity.status(409).body(InventoryDtos.ErrorResponse.of("CONFLICT", e.getMessage(), 409));
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<InventoryDtos.ErrorResponse> badRequest(BadRequestException e) {
        return ResponseEntity.status(400).body(InventoryDtos.ErrorResponse.of("BAD_REQUEST", e.getMessage(), 400));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<InventoryDtos.ErrorResponse> forbidden(ForbiddenException e) {
        return ResponseEntity.status(403).body(InventoryDtos.ErrorResponse.of("FORBIDDEN", e.getMessage(), 403));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<InventoryDtos.ErrorResponse> accessDenied(AccessDeniedException e) {
        return ResponseEntity.status(403).body(InventoryDtos.ErrorResponse.of("FORBIDDEN", "Access denied", 403));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<InventoryDtos.ErrorResponse> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(Collectors.joining(", "));
        return ResponseEntity.status(400).body(InventoryDtos.ErrorResponse.of("VALIDATION_ERROR", msg, 400));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<InventoryDtos.ErrorResponse> generic(Exception e) {
        log.error("Unhandled: {}", e.getMessage(), e);
        return ResponseEntity.status(500)
                .body(InventoryDtos.ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", 500));
    }
}
