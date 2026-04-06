package io.tradeflow.payment.config;

import io.tradeflow.payment.dto.PaymentDtos;
import io.tradeflow.payment.service.InvalidRefundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class InvalidRefundMapper implements ExceptionMapper<InvalidRefundException> {
    @Override
    public Response toResponse(InvalidRefundException e) {
        return Response.status(422)
                .entity(PaymentDtos.ErrorResponse.of("INVALID_REFUND", e.getMessage(), 422))
                .build();
    }
}
