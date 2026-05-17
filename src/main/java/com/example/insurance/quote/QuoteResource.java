package com.example.insurance.quote;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Duration;

@Path("/quotes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class QuoteResource {

    private static final int      QUOTE_LIMIT_PER_WINDOW = 5;
    private static final Duration QUOTE_LIMIT_WINDOW     = Duration.ofSeconds(60);

    @Inject
    QuoteService service;

    @Inject
    RateLimiter rateLimiter;

    @Inject
    JsonWebToken jwt;

    @Context
    UriInfo uriInfo;

    @POST
    @RolesAllowed("APPLICATION")
    // @Valid triggers Jakarta Bean Validation against the constraint
    // annotations on QuoteRequest. Failed validation surfaces as a
    // ConstraintViolationException, which our
    // ConstraintViolationExceptionMapper maps to a 400 with a per-field
    // violations list (issue #62). The manual null/blank checks that
    // used to live here are now covered by @NotBlank / @NotNull on the
    // record components.
    public Response create(@Valid QuoteRequest req) {

        // Per ADR 0005: rate-limit per customer_id; until identity lands we
        // key by vehicleVin as a stand-in.
        String rlKey = "ratelimit:quote:" + req.vehicleVin();
        if (!rateLimiter.allow(rlKey, QUOTE_LIMIT_PER_WINDOW, QUOTE_LIMIT_WINDOW)) {
            return Response.status(429)
                    .entity("{\"error\":\"rate limit exceeded for vehicleVin\"}")
                    .build();
        }

        try {
            Quote q = service.createQuote(req);
            var location = uriInfo.getAbsolutePathBuilder().path(String.valueOf(q.getId())).build();
            return Response.created(location).entity(q).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") Long id) {
        Quote q = service.getById(id);
        if (q == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(q).build();
    }
}
