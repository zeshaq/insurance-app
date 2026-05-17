package com.example.insurance.error;

import jakarta.json.bind.JsonbException;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonbExceptionMapperTest {

    @Test
    void mapsJsonbExceptionToBadRequest() {
        Response r = new JsonbExceptionMapper().toResponse(new JsonbException("expected object, got array"));

        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getMediaType().toString()).isEqualTo("application/json");
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) r.getEntity();
        assertThat(body.get("error")).isEqualTo("malformed JSON body");
        assertThat((String) body.get("detail")).contains("expected object, got array");
    }

    @Test
    void tolerantOfNullMessage() {
        Response r = new JsonbExceptionMapper().toResponse(new JsonbException(null));

        assertThat(r.getStatus()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) r.getEntity();
        assertThat((String) body.get("detail")).isNotNull().isNotEmpty();
    }
}
