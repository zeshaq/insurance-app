package com.example.insurance.error;

import jakarta.json.JsonException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maps {@link JsonException} (and its subclass {@code JsonbException}) to a 400.
 *
 * <p>Without this, a POST endpoint that receives a malformed JSON body throws a
 * Yasson parse error that bubbles up as a default 500. Schemathesis flagged
 * the regression in Phase 2 (issue #51): POST /api/quotes, /api/policies, and
 * /api/payments all returned 500 instead of 400 when sent a null byte, a
 * deeply-nested array, or other JSON the entity record can't deserialise.
 *
 * <p>The mapper is registered automatically because the class carries
 * {@link Provider} and Liberty's JAX-RS implementation scans for providers.
 */
@Provider
public class JsonExceptionMapper implements ExceptionMapper<JsonException> {

    private static final Logger LOG = Logger.getLogger(JsonExceptionMapper.class.getName());

    @Override
    public Response toResponse(JsonException ex) {
        LOG.log(Level.FINE, "client sent malformed JSON body", ex);
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of(
                        "error",  "malformed JSON body",
                        "detail", ex.getMessage() == null ? "could not parse request body" : ex.getMessage()))
                .build();
    }
}
