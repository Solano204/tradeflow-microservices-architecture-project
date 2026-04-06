package io.tradeflow.payment.config;

import io.tradeflow.payment.dto.PaymentDtos;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

@Provider
@Slf4j
public class GenericExceptionMapper implements ExceptionMapper<Exception> {
    @Override
    public Response toResponse(Exception e) {
        // Don't log JAX-RS WebApplicationException at ERROR level
        if (e instanceof jakarta.ws.rs.WebApplicationException wae) {
            return wae.getResponse();
        }
        log.error("Unhandled exception: {}", e.getMessage(), e);
        return Response.status(500)
                .entity(PaymentDtos.ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", 500))
                .build();
    }
}
