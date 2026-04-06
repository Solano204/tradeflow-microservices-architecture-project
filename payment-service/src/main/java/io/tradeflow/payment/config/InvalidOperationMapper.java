package io.tradeflow.payment.config;

import io.tradeflow.payment.dto.PaymentDtos;
import io.tradeflow.payment.service.InvalidOperationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class InvalidOperationMapper implements ExceptionMapper<InvalidOperationException> {
    @Override
    public Response toResponse(InvalidOperationException e) {
        return Response.status(409)
                .entity(PaymentDtos.ErrorResponse.of("CONFLICT", e.getMessage(), 409))
                .build();
    }
}
