package com.example.insurance.claim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Separate bean so {@link Retry} fires across the CDI proxy boundary —
 * see build_gotchas item 14 for why same-bean self-invocations skip
 * mpFaultTolerance interceptors. Same pattern as PaymentGatewayInvoker.
 */
@ApplicationScoped
public class OcrInvoker {

    @Inject
    @RestClient
    OcrClient client;

    @Retry(maxRetries = 2, delay = 200)
    public OcrResponse extract(OcrRequest req) {
        return client.extract(req);
    }
}
