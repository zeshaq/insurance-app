package com.example.insurance.notification;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * mpRestClient for MI's channel router. configKey {@code notification-mi}
 * binds to {@code http://insurance-mi:8290/notification} via
 * microprofile-config.properties.
 *
 * Returns {@link Response} (not a typed DTO) so we can inspect the status
 * code per channel and react — Mailpit returns 200, WireMock stubs return
 * 200 with a stubbed body. A 5xx means the channel was unavailable and
 * the audit row flips to FAILED.
 */
@RegisterRestClient(configKey = "notification-mi")
@Path("/dispatch")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface NotificationDispatcher {

    @POST
    Response dispatch(NotificationRequest req);
}
