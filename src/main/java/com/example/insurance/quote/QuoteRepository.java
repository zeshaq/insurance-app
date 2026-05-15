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
            return q;
        }
        return em.merge(q);
    }

    public Quote findById(Long id) {
        return em.find(Quote.class, id);
    }
}
