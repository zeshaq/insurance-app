package com.example.insurance.claim;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/claims")
@Produces(MediaType.APPLICATION_JSON)
public class ClaimResource {

    private static final Logger LOG = Logger.getLogger(ClaimResource.class.getName());

    @Inject ClaimService    service;
    @Inject ClaimRepository repo;

    /**
     * Multipart upload. Jakarta REST 3.1 introduced {@link EntityPart} as
     * the portable multipart abstraction — no RESTEasy- or Jersey-specific
     * @FormDataParam needed. Expected parts:
     *   policyNumber   (text)  — must reference an existing policy
     *   description    (text, optional)
     *   attachment     (file, optional) — claim photo / PDF
     *
     * The attachment is streamed to MinIO; large uploads never have to
     * fit in heap.
     */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed("APPLICATION")
    public Response file(List<EntityPart> parts) {
        String policyNumber  = null;
        String description   = null;
        String otherPartyVin = null;
        InputStream content  = null;
        long contentLength   = 0;
        String contentType   = null;
        String originalName  = null;

        for (EntityPart part : parts) {
            switch (part.getName()) {
                case "policyNumber"   -> { try { policyNumber   = part.getContent(String.class); } catch (Exception ignored) {} }
                case "description"    -> { try { description    = part.getContent(String.class); } catch (Exception ignored) {} }
                case "otherPartyVin"  -> { try { otherPartyVin  = part.getContent(String.class); } catch (Exception ignored) {} }
                case "attachment"   -> {
                    content      = part.getContent();
                    contentType  = part.getMediaType() == null ? null : part.getMediaType().toString();
                    originalName = part.getFileName().orElse(null);
                    contentLength = -1;   // EntityPart does not expose size; MinIO streams in 5MB parts.
                }
                default -> { /* ignore */ }
            }
        }
        if (policyNumber == null || policyNumber.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"policyNumber part is required\"}").build();
        }
        try {
            Claim c = service.file(policyNumber, description, content, contentLength, contentType, originalName, otherPartyVin);
            return Response.status(Response.Status.CREATED).entity(c).build();
        } catch (jakarta.ws.rs.NotFoundException nf) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"" + nf.getMessage() + "\"}").build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Claim filing failed for policy " + policyNumber, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"upload failed\"}").build();
        }
    }

    /** List recent claims. Anonymous read for the demo — same trade-off
     *  as /api/policies. POST/approve remain @RolesAllowed. */
    @GET
    public java.util.List<Claim> list(@QueryParam("limit") Integer limit) {
        return repo.findRecent(limit == null ? 50 : limit);
    }

    @GET
    @Path("/{id}")
    public Claim get(@PathParam("id") Long id) {
        return service.getById(id);
    }

    /** Operator workflow stub: flips the claim status to APPROVED + audits it. */
    @POST
    @Path("/{id}/approve")
    @jakarta.annotation.security.RolesAllowed("APPLICATION")
    public Claim approve(@PathParam("id") Long id) {
        return service.approve(id);
    }
}
