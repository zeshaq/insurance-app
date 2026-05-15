package com.example.insurance.claim;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * mTLS client for the partner carrier's cross-coverage lookup API.
 *
 * configKey {@code partner} in microprofile-config.properties binds to:
 *   https://partner-mock:8443/partner
 * with {@code partner/mp-rest/outboundSSLRef=partnerMtls} pointing at the
 * server.xml {@code <ssl id="partnerMtls">} element. That ssl config holds
 * the keyStore (our client cert+key) and trustStore (the CA that signed
 * partner-mock's server cert).
 */
@RegisterRestClient(configKey = "partner")
@Path("/lookup")
@Produces(MediaType.APPLICATION_JSON)
public interface PartnerClient {

    @GET
    PartnerResponse lookup(@QueryParam("vin") String vin);
}
