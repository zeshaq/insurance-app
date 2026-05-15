package com.example.insurance.claim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ClaimRepository {

    @PersistenceContext(unitName = "insurance")
    private EntityManager em;

    @Transactional
    public Claim save(Claim c) {
        if (c.getId() == null) {
            em.persist(c);
            em.flush();
            return c;
        }
        return em.merge(c);
    }

    public Claim findById(Long id) {
        return em.find(Claim.class, id);
    }
}
