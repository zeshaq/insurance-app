package com.example.insurance.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ConstraintViolationExceptionMapperTest {

    @Test
    void mapsToBadRequest_withPerFieldViolations() {
        ConstraintViolation<?> v1 = stubViolation("quoteRequest.vehicleVin",
                "vehicleVin must be 3-17 characters (ISO 3779 caps at 17)");
        ConstraintViolation<?> v2 = stubViolation("quoteRequest.driverAge",
                "driverAge must be at least 16");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(v1, v2));

        Response r = new ConstraintViolationExceptionMapper().toResponse(ex);

        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getMediaType().toString()).isEqualTo("application/json");
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) r.getEntity();
        assertThat(body.get("error")).isEqualTo("validation failed");
        @SuppressWarnings("unchecked")
        var violations = (List<Map<String, String>>) body.get("violations");
        assertThat(violations).hasSize(2);
        assertThat(violations)
                .extracting(m -> m.get("field"))
                .contains("quoteRequest.vehicleVin", "quoteRequest.driverAge");
    }

    @Test
    void handlesEmptyViolationsSet() {
        ConstraintViolationException ex = new ConstraintViolationException(Set.of());
        Response r = new ConstraintViolationExceptionMapper().toResponse(ex);

        assertThat(r.getStatus()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) r.getEntity();
        @SuppressWarnings("unchecked")
        var violations = (List<?>) body.get("violations");
        assertThat(violations).isEmpty();
    }

    private static ConstraintViolation<?> stubViolation(String path, String msg) {
        ConstraintViolation<?> v = Mockito.mock(ConstraintViolation.class);
        when(v.getMessage()).thenReturn(msg);
        jakarta.validation.Path p = Mockito.mock(jakarta.validation.Path.class);
        when(p.toString()).thenReturn(path);
        when(v.getPropertyPath()).thenReturn(p);
        return v;
    }
}
