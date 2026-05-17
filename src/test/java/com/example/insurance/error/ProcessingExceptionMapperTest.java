package com.example.insurance.error;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingExceptionMapperTest {

    @Test
    void mapsProcessingExceptionToBadRequest() {
        Response r = new ProcessingExceptionMapper()
                .toResponse(new ProcessingException("malformed multipart boundary"));

        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getMediaType().toString()).isEqualTo("application/json");
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) r.getEntity();
        assertThat(body.get("error")).isEqualTo("malformed request");
        assertThat((String) body.get("detail")).contains("multipart boundary");
    }
}
