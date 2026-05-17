package com.example.insurance.error;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maps {@link ProcessingException} to a 400.
 *
 * <p>Liberty's RESTEasy throws this for malformed multipart bodies (and a
 * few other request-side processing failures). The bug Schemathesis caught
 * in Phase 2 (issue #51): POST /api/claims with a malformed multipart body
 * 500'd before reaching the resource method's try/catch.
 */
@Provider
public class ProcessingExceptionMapper implements ExceptionMapper<ProcessingException> {

    private static final Logger LOG = Logger.getLogger(ProcessingExceptionMapper.class.getName());

    @Override
    public Response toResponse(ProcessingException ex) {
        LOG.log(Level.FINE, "client sent a request the framework could not parse", ex);
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of(
                        "error",  "malformed request",
                        "detail", ex.getMessage() == null ? "could not process request body" : ex.getMessage()))
                .build();
    }
}
