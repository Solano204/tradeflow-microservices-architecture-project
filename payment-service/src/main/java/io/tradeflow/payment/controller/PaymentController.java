package io.tradeflow.payment.controller;

import io.smallrye.mutiny.Uni;
import io.tradeflow.payment.dto.PaymentDtos.*;
import io.tradeflow.payment.service.PaymentService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;

@ApplicationScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Payments", description = "Reactive payment processing, idempotency, dunning")
@Slf4j
public class PaymentController {

    @Inject PaymentService paymentService;

    @POST
    @Path("/payments/charge")
    @RolesAllowed({"SERVICE", "ADMIN"})
    @Operation(summary = "Charge buyer — idempotent, reactive, non-blocking")
    public Uni<Response> charge(@Valid ChargeRequest request) {
        return paymentService.charge(request)
                .map(result -> Response.status(Response.Status.CREATED).entity(result).build());
    }

    @POST
    @Path("/payments/refund")
    @RolesAllowed({"SERVICE", "ADMIN"})
    @Operation(summary = "Issue full or partial refund")
    public Uni<Response> refund(@Valid RefundRequest request) {
        return paymentService.refund(request)
                .map(result -> Response.ok(result).build());
    }

    @POST
    @Path("/payments/webhooks/stripe")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Stripe webhook receiver — HMAC verified, 200 returned immediately")
    public Uni<Response> stripeWebhook(
            @HeaderParam("Stripe-Signature") String signature,
            StripeWebhookEvent event) {

        if (signature == null || signature.isBlank()) {
            log.warn("Stripe webhook missing signature header — rejecting");
            return Uni.createFrom().item(Response.status(400)
                    .entity(ErrorResponse.of("INVALID_SIGNATURE", "Missing Stripe-Signature header", 400))
                    .build());
        }

        log.debug("Stripe webhook received: type={}, id={}", event.type(), event.id());

        paymentService.processWebhook(event)
                .subscribe().with(
                        __ -> log.debug("Stripe webhook processed: eventId={}", event.id()),
                        err -> log.error("Stripe webhook processing failed: eventId={}, error={}", event.id(), err.getMessage()));

        return Uni.createFrom().item(Response.ok(Map.of("received", true)).build());
    }

    @GET
    @Path("/payments/{orderId}")
    @RolesAllowed({"BUYER", "MERCHANT", "SERVICE", "ADMIN"})
    @Operation(summary = "Get payment status for an order")
    public Uni<Response> getPaymentStatus(
            @PathParam("orderId") String orderId,
            @Context SecurityContext sec) {
        return paymentService.getPaymentStatus(orderId)
                .map(result -> Response.ok(result).build());
    }

    @GET
    @Path("/payments/{orderId}/receipt")
    @RolesAllowed({"BUYER", "ADMIN"})
    @Operation(summary = "Get payment receipt (immutable, cached)")
    public Uni<Response> getReceipt(
            @PathParam("orderId") String orderId,
            @Context SecurityContext sec) {
        String buyerId = sec.getUserPrincipal() != null ? sec.getUserPrincipal().getName() : "unknown";
        return paymentService.getReceipt(orderId, buyerId)
                .map(receipt -> Response.ok(receipt).build());
    }

    @POST
    @Path("/internal/payments/dunning/tick")
    @RolesAllowed({"ADMIN", "SERVICE"})
    @Operation(summary = "Dunning tick — advance stuck merchant fee retry state machine")
    public Uni<Response> dunningTick() {
        return paymentService.dunningTick()
                .map(__ -> Response.ok(Map.of("status", "tick_processed")).build());
    }

    @GET
    @Path("/internal/payments/{orderId}/status")
    @Operation(summary = "Internal SAGA recovery fast-path — no JWT, NetworkPolicy only")
    public Uni<Response> getInternalStatus(@PathParam("orderId") String orderId) {
        return paymentService.getInternalStatus(orderId)
                .map(result -> Response.ok(result).build());
    }

    @POST
    @Path("/payments/disputes/{disputeId}/respond")
    @RolesAllowed({"ADMIN", "COMPLIANCE"})
    @Operation(summary = "Submit dispute evidence to Stripe")
    public Uni<Response> respondToDispute(
            @PathParam("disputeId") String disputeId,
            @Valid DisputeEvidenceRequest request) {
        return paymentService.respondToDispute(disputeId, request)
                .map(__ -> Response.ok(Map.of("status", "evidence_submitted")).build());
    }
}