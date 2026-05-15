package com.example.insurance.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class PaymentRepository {

    @PersistenceContext(unitName = "insurance")
    private EntityManager em;

    @Transactional
    public Payment save(Payment p) {
        if (p.getId() == null) {
            em.persist(p);
            em.flush();
            return p;
        }
        return em.merge(p);
    }

    public Payment findByIdempotencyKey(String key) {
        try {
            return em.createQuery("SELECT p FROM Payment p WHERE p.idempotencyKey = :k", Payment.class)
                    .setParameter("k", key)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    public Payment findById(Long id) {
        return em.find(Payment.class, id);
    }
}
