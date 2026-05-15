package com.example.insurance.quote;

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

@Path("/quotes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class QuoteResource {

    @Inject
    QuoteService service;

    @Inject
    QuoteRepository repo;

    @Context
    UriInfo uriInfo;

    @POST
    public Response create(QuoteRequest req) {
        if (req == null
                || req.vehicleVin() == null || req.vehicleVin().isBlank()
                || req.driverAge() == null
                || req.coverageType() == null || req.coverageType().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"vehicleVin, driverAge, coverageType are required\"}")
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
        Quote q = repo.findById(id);
        if (q == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(q).build();
    }
}
