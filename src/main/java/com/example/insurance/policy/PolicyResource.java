package com.example.insurance.policy;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PolicyResource {

    @Inject PolicyService    service;
    @Inject PolicyRepository repo;

    @POST
    @RolesAllowed("APPLICATION")
    public Response bind(PolicyRequest req) {
        if (req == null || req.quoteId() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"quoteId is required\"}").build();
        }
        PolicyService.BindResult r = service.bind(req.quoteId());
        return Response.status(r.created() ? Response.Status.CREATED : Response.Status.OK)
                .entity(r.policy()).build();
    }

    /** List recent policies. No auth on read for the demo — Liberty's
     *  @RolesAllowed on POST is the only auth gate; reads are public so
     *  the customer-app /policies page can render without a session. */
    @GET
    public java.util.List<Policy> list(@QueryParam("limit") Integer limit) {
        return repo.findRecent(limit == null ? 50 : limit);
    }

    @GET
    @Path("/{policyNumber}")
    public Policy get(@PathParam("policyNumber") String policyNumber) {
        Policy p = service.getByNumber(policyNumber);
        if (p == null) throw new NotFoundException();
        return p;
    }
}
