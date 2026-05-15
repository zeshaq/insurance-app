package com.example.insurance.report;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Snapshot endpoint hit by MI's scheduled task. Public on the insurance-net
 * bridge but not externally exposed — MI is the only intended caller. No JWT
 * gate so the scheduler doesn't have to manage credentials; trust comes from
 * the network boundary (the VM bridge).
 */
@Path("/reports")
@Produces(MediaType.APPLICATION_JSON)
public class ReportSnapshotResource {

    @Inject PaymentReportStreams  streams;
    @Inject ReportRunRepository   repo;

    @POST
    @Path("/snapshot")
    @Transactional
    public ReportRun snapshot(@QueryParam("source") String source) {
        Map<String, Long> totals = streams.totalsByStatusLastHour();
        ReportRun r = new ReportRun();
        r.setSucceededCount(totals.getOrDefault("SUCCEEDED", 0L));
        r.setFailedCount(totals.getOrDefault("FAILED", 0L));
        r.setUnknownCount(totals.getOrDefault("UNKNOWN", 0L));
        r.setSource(source == null || source.isBlank() ? "mi-scheduled-task" : source);
        r.setCreatedAt(OffsetDateTime.now());
        return repo.save(r);
    }

    @GET
    @Path("/runs")
    public List<ReportRun> runs(@QueryParam("limit") Integer limit) {
        return repo.recent(limit == null ? 20 : Math.max(1, Math.min(200, limit)));
    }

    @GET
    @Path("/runs/count")
    public Map<String, Long> count() {
        return Map.of("total", repo.count());
    }
}
