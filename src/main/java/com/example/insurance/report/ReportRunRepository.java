package com.example.insurance.report;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
public class ReportRunRepository {

    @PersistenceContext(unitName = "insurance")
    private EntityManager em;

    @Transactional
    public ReportRun save(ReportRun r) {
        em.persist(r);
        em.flush();
        return r;
    }

    public List<ReportRun> recent(int limit) {
        return em.createQuery("SELECT r FROM ReportRun r ORDER BY r.createdAt DESC", ReportRun.class)
                .setMaxResults(limit)
                .getResultList();
    }

    public long count() {
        return em.createQuery("SELECT COUNT(r) FROM ReportRun r", Long.class).getSingleResult();
    }
}
