package com.example.insurance.quote;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class QuoteRepository {

    @PersistenceContext(unitName = "insurance")
    private EntityManager em;

    @Transactional
    public Quote save(Quote q) {
        if (q.getId() == null) {
            em.persist(q);
            // Force INSERT now so the IDENTITY-generated id is assigned to `q`.
            // Without flush(), EclipseLink defers the INSERT until JTA commit,
            // so q.getId() is still null when callers (e.g. QuoteService)
            // immediately hand the entity to the cache or to a Kafka emit.
            // The cache then silently writes every quote to key "quote:null".
            em.flush();
            return q;
        }
        return em.merge(q);
    }

    public Quote findById(Long id) {
        return em.find(Quote.class, id);
    }
}
