package com.example.insurance.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maps {@link ConstraintViolationException} to a 400.
 *
 * <p>Jakarta Bean Validation throws this when a request body fails the
 * constraint annotations on the entity record (e.g. {@code @Size(max=17)}
 * on a VIN). Without this mapper, Liberty would emit a 500 — issue #62
 * surfaced exactly this regression on POST /api/quotes during Phase 3
 * load testing.
 *
 * <p>Response body shape:
 * <pre>{@code
 * {
 *   "error": "validation failed",
 *   "violations": [
 *     {"field": "quoteRequest.vehicleVin", "message": "vehicleVin must be 3-17 characters (ISO 3779 caps at 17)"},
 *     ...
 *   ]
 * }
 * }</pre>
 */
@Provider
public class ConstraintViolationExceptionMapper
        implements ExceptionMapper<ConstraintViolationException> {

    private static final Logger LOG = Logger.getLogger(ConstraintViolationExceptionMapper.class.getName());

    @Override
    public Response toResponse(ConstraintViolationException ex) {
        LOG.log(Level.FINE, "client sent a request that violates bean-validation constraints", ex);

        List<Map<String, String>> violations = ex.getConstraintViolations().stream()
                .map(this::renderViolation)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation failed");
        body.put("violations", violations);

        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }

    private Map<String, String> renderViolation(ConstraintViolation<?> v) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("field", v.getPropertyPath().toString());
        out.put("message", v.getMessage());
        return out;
    }
}
