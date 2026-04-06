package io.tradeflow.payment.service;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Map;

@RegisterRestClient(configKey = "stripe-api")
@RegisterClientHeaders
interface StripeRestClient {

    @POST
    @Path("/v1/charges")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Map<String, Object>> createCharge(
            @HeaderParam("Authorization") String auth,
            @HeaderParam("Idempotency-Key") String idempotencyKey,
            Map<String, Object> params);

    @POST
    @Path("/v1/refunds")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Map<String, Object>> createRefund(
            @HeaderParam("Authorization") String auth,
            @HeaderParam("Idempotency-Key") String idempotencyKey,
            Map<String, Object> params);

    @POST
    @Path("/v1/disputes/{disputeId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<Map<String, Object>> updateDisputeEvidence(
            @HeaderParam("Authorization") String auth,
            @PathParam("disputeId") String disputeId,
            Map<String, Object> params);
}
