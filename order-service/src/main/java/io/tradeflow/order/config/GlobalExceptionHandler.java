package io.tradeflow.order.config;

import io.tradeflow.order.dto.OrderDtos;
import io.tradeflow.order.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<OrderDtos.ErrorResponse> notFound(NotFoundException e) {
        return ResponseEntity.status(404).body(OrderDtos.ErrorResponse.of("NOT_FOUND", e.getMessage(), 404));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<OrderDtos.ErrorResponse> conflict(ConflictException e) {
        return ResponseEntity.status(409).body(OrderDtos.ErrorResponse.of("CONFLICT", e.getMessage(), 409));
    }

    @ExceptionHandler(BusinessValidationException.class)
    ResponseEntity<OrderDtos.ErrorResponse> businessValidation(BusinessValidationException e) {
        return ResponseEntity.status(422).body(OrderDtos.ErrorResponse.of("VALIDATION_FAILED", e.getMessage(), 422));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<OrderDtos.ErrorResponse> forbidden(ForbiddenException e) {
        return ResponseEntity.status(403).body(OrderDtos.ErrorResponse.of("FORBIDDEN", e.getMessage(), 403));
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<OrderDtos.ErrorResponse> badRequest(BadRequestException e) {
        return ResponseEntity.status(400).body(OrderDtos.ErrorResponse.of("BAD_REQUEST", e.getMessage(), 400));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<OrderDtos.ErrorResponse> accessDenied(AccessDeniedException e) {
        return ResponseEntity.status(403).body(OrderDtos.ErrorResponse.of("FORBIDDEN", "Access denied", 403));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<OrderDtos.ErrorResponse> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(Collectors.joining(", "));
        return ResponseEntity.status(400).body(OrderDtos.ErrorResponse.of("VALIDATION_ERROR", msg, 400));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<OrderDtos.ErrorResponse> generic(Exception e) {
        log.error("Unhandled: {}", e.getMessage(), e);
        return ResponseEntity.status(500)
                .body(OrderDtos.ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", 500));
    }
}
