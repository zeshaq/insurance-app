package com.example.insurance.payment;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * mpRestClient for the payment gateway. The actual gateway is mocked by
 * WireMock; Liberty hits MI at {@code http://insurance-mi:8290/payment} and
 * MI forwards to {@code http://wiremock:8080/payment-gateway/charge}. The
 * same indirection pattern as CreditBureauClient — credentials, mTLS, and
 * retry/circuit-breaker policies would live on the MI side in a real
 * deployment.
 */
@RegisterRestClient(configKey = "payment-gateway")
@Path("/charge")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface PaymentGatewayClient {

    @POST
    PaymentGatewayResponse charge(PaymentGatewayChargeRequest req);
}
