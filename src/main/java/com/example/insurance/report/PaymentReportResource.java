package com.example.insurance.report;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/reports/payment-stats")
@Produces(MediaType.APPLICATION_JSON)
public class PaymentReportResource {

    @Inject PaymentReportStreams streams;

    @GET
    public Response get() {
        return new Response(
                streams.totalsByStatusLastHour(),
                streams.countByStatusLastHour());
    }

    public record Response(Map<String, Long> totalsByStatus,
                           java.util.List<PaymentReportStreams.WindowedCount> windows) {}
}
