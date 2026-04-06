package io.tradeflow.auth.config;

import io.tradeflow.auth.dto.AuthDtos.ErrorResponse;
import io.tradeflow.auth.service.AuthService.AuthException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Maps AuthException to HTTP responses with RFC 6749 error format.
 */
@Provider
public class AuthExceptionMapper implements ExceptionMapper<AuthException> {

    private static final Logger LOG = Logger.getLogger(AuthExceptionMapper.class);

    @Override
    public Response toResponse(AuthException exception) {
        // Log 5xx errors fully; 4xx are expected flows
        if (exception.status.getStatusCode() >= 500) {
            LOG.error("Auth service error: " + exception.description, exception);
        } else {
            LOG.debugf("Auth flow rejection: [%s] %s", exception.errorCode, exception.description);
        }

        return Response
                .status(exception.status)
                .entity(ErrorResponse.of(exception.errorCode, exception.description))
                .build();
    }
}