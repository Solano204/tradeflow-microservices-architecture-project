package io.tradeflow.search;

import io.tradeflow.search.dto.SearchDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<SearchDtos.ErrorResponse> statusEx(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(SearchDtos.ErrorResponse.of(String.valueOf(e.getStatusCode().value()), e.getReason() != null ? e.getReason() : e.getMessage(), e.getStatusCode().value()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<SearchDtos.ErrorResponse> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(403).body(SearchDtos.ErrorResponse.of("FORBIDDEN", "Access denied", 403));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<SearchDtos.ErrorResponse> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(Collectors.joining(", "));
        return ResponseEntity.status(400).body(SearchDtos.ErrorResponse.of("VALIDATION_ERROR", msg, 400));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<SearchDtos.ErrorResponse> generic(Exception e) {
        log.error("Unhandled: {}", e.getMessage(), e);
        return ResponseEntity.status(500).body(SearchDtos.ErrorResponse.of("INTERNAL_ERROR", "Unexpected error", 500));
    }
}
