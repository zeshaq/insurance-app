package com.example.insurance.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Thin wrapper around {@link PaymentGatewayClient} that exists *only* so
 * the {@link Retry} interceptor fires.
 *
 * mpFaultTolerance's interceptors run on CDI proxy boundaries. If
 * PaymentService called {@code this.chargeWithRetry(...)} on itself, the
 * call would skip the proxy and the interceptor would never see it — the
 * symptom is exactly the bug slice 7 first shipped with: gateway sees one
 * call, no retries, DLQ never fires for the right reason. Putting the
 * annotated method on a separate bean and injecting it forces the call
 * through the proxy, where @Retry can do its job.
 */
@ApplicationScoped
public class PaymentGatewayInvoker {

    @Inject
    @RestClient
    PaymentGatewayClient gateway;

    @Retry(maxRetries = 2, delay = 200)
    public PaymentGatewayResponse charge(PaymentGatewayChargeRequest req) {
        return gateway.charge(req);
    }
}
