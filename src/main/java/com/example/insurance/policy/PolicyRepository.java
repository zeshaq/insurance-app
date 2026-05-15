package com.example.insurance.policy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class PolicyRepository {

    @PersistenceContext(unitName = "insurance")
    private EntityManager em;

    @Transactional
    public Policy save(Policy p) {
        if (em.find(Policy.class, p.getPolicyNumber()) == null) {
            em.persist(p);
            em.flush();
            return p;
        }
        return em.merge(p);
    }

    public Policy findByNumber(String policyNumber) {
        return em.find(Policy.class, policyNumber);
    }

    public Policy findByQuoteId(Long quoteId) {
        try {
            return em.createQuery("SELECT p FROM Policy p WHERE p.quoteId = :q", Policy.class)
                    .setParameter("q", quoteId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
}
