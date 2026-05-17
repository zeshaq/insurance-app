package com.example.insurance.error;

import jakarta.json.JsonException;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonExceptionMapperTest {

    @Test
    void mapsJsonExceptionToBadRequest() {
        Response r = new JsonExceptionMapper().toResponse(new JsonException("unexpected end of stream"));

        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getMediaType().toString()).isEqualTo("application/json");
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) r.getEntity();
        assertThat(body.get("error")).isEqualTo("malformed JSON body");
        assertThat((String) body.get("detail")).contains("unexpected end of stream");
    }

    @Test
    void tolerantOfNullMessage() {
        Response r = new JsonExceptionMapper().toResponse(new JsonException((String) null));

        assertThat(r.getStatus()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) r.getEntity();
        assertThat((String) body.get("detail")).isNotNull().isNotEmpty();
    }
}
