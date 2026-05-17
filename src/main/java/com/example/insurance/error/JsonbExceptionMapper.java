package com.example.insurance.error;

import jakarta.json.bind.JsonbException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maps {@link JsonbException} to a 400.
 *
 * <p>Yasson (Liberty's JSON-B implementation) throws this when a POST body
 * can't be bound to the declared entity record — for example a nested array
 * where an object is expected, or a stray null byte. Without a mapper the
 * exception bubbles up as a default 500. Phase 2 Schemathesis caught the
 * regression on POST /api/quotes, /api/policies, and /api/payments (issue
 * #51). {@link JsonExceptionMapper} sits beside this one to catch raw
 * {@code jakarta.json.JsonException}s (lower-level parse failures) —
 * {@code JsonbException} is intentionally not a subclass of {@code JsonException},
 * so we need both mappers.
 */
@Provider
public class JsonbExceptionMapper implements ExceptionMapper<JsonbException> {

    private static final Logger LOG = Logger.getLogger(JsonbExceptionMapper.class.getName());

    @Override
    public Response toResponse(JsonbException ex) {
        LOG.log(Level.FINE, "client sent a JSON body that does not match the endpoint schema", ex);
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of(
                        "error",  "malformed JSON body",
                        "detail", ex.getMessage() == null ? "could not bind request body to expected type" : ex.getMessage()))
                .build();
    }
}
