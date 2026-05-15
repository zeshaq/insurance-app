package com.example.insurance.quote;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * MicroProfile Rest Client interface for the credit-bureau lookup, routed
 * through WSO2 MI. The actual base URL lives in
 * `META-INF/microprofile-config.properties` under the configKey
 * "credit-bureau" — MI's host is {@code insurance-mi:8290} on the shared
 * podman bridge, and the upstream WireMock stub stands in for a paid
 * credit bureau (Experian / TransUnion / etc.) in slices 4+.
 */
@RegisterRestClient(configKey = "credit-bureau")
@Path("/check")
@Produces(MediaType.APPLICATION_JSON)
public interface CreditBureauClient {

    @GET
    CreditScore lookup(@QueryParam("vin") String vin);
}
