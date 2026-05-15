package com.example.insurance.payment;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PaymentResource {

    @Inject PaymentService service;

    /**
     * Idempotency-Key header is REQUIRED. The same key replays the same
     * Payment regardless of how many times the client retries — first
     * SUCCEEDED call returns 201, replays return 200 with the same body.
     * Missing key is a client bug (400), not a server fallback to
     * "auto-generate one": the client needs to control the key so its own
     * retries collapse onto the same charge.
     *
     * Failed payments still get a durable record and return 502 Bad
     * Gateway — replays of a failed key return 200 with status=FAILED so
     * the client can distinguish "did my retry charge twice" (no, same
     * record) from "should I keep retrying" (probably not, gateway is
     * down — that's what the DLQ alert is for).
     */
    @POST
    @RolesAllowed("APPLICATION")
    public Response charge(@HeaderParam("Idempotency-Key") String idempotencyKey,
                           PaymentRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Idempotency-Key header is required\"}").build();
        }
        if (req == null || req.policyNumber() == null || req.amount() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"policyNumber and amount are required\"}").build();
        }
        PaymentService.Result r = service.process(idempotencyKey, req);
        Response.Status code = r.replayed()
                ? Response.Status.OK
                : ("FAILED".equals(r.payment().getStatus())
                        ? Response.Status.BAD_GATEWAY
                        : Response.Status.CREATED);
        return Response.status(code).entity(r.payment()).build();
    }

    @GET
    @Path("/{id}")
    public Payment get(@PathParam("id") Long id) {
        Payment p = service.getById(id);
        if (p == null) throw new NotFoundException();
        return p;
    }
}
