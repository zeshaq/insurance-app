package com.example.insurance.claim;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * mpRestClient for MI's OCR mediator. configKey {@code ocr-mi} binds to
 * {@code http://insurance-mi:8290/ocr} via microprofile-config.properties.
 * MI translates and forwards to the (mocked) vision API.
 */
@RegisterRestClient(configKey = "ocr-mi")
@Path("/extract")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface OcrClient {

    @POST
    OcrResponse extract(OcrRequest req);
}
