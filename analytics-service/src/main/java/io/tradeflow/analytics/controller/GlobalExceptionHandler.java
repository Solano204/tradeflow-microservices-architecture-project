package io.tradeflow.analytics.controller;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Kafka Streams state store not available — streams may be rebalancing.
     */
    @ExceptionHandler(InvalidStateStoreException.class)
    public ProblemDetail handleStreamsUnavailable(InvalidStateStoreException e) {
        log.warn("Kafka Streams store unavailable: {}", e.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Analytics data is temporarily unavailable. Kafka Streams is rebalancing.");
        detail.setType(URI.create("https://tradeflow.io/errors/streams-unavailable"));
        detail.setProperty("retry_after_seconds", 10);
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        detail.setType(URI.create("https://tradeflow.io/errors/bad-request"));
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception e) {
        log.error("Unhandled exception: {}", e.getMessage(), e);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        detail.setType(URI.create("https://tradeflow.io/errors/internal"));
        return detail;
    }
}
