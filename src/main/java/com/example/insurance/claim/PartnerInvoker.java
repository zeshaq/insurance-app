package com.example.insurance.claim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Same CDI-proxy-boundary discipline as OcrInvoker / PaymentGatewayInvoker
 * (gotcha 14) — @Retry only fires when the call crosses a proxy.
 *
 * Two retries because partner APIs are inherently flaky: cross-carrier
 * traffic crosses peering, NAT, and rate-limit boundaries that the local
 * Kafka cluster doesn't have.
 */
@ApplicationScoped
public class PartnerInvoker {

    @Inject
    @RestClient
    PartnerClient client;

    @Retry(maxRetries = 2, delay = 300)
    public PartnerResponse lookup(String vin) {
        return client.lookup(vin);
    }
}
