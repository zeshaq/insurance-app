package com.example.insurance.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
public class NotificationRepository {

    @PersistenceContext(unitName = "insurance")
    private EntityManager em;

    @Transactional
    public Notification save(Notification n) {
        if (n.getId() == null) {
            em.persist(n);
            em.flush();
            return n;
        }
        return em.merge(n);
    }

    public Notification findById(Long id) {
        return em.find(Notification.class, id);
    }

    public List<Notification> findByEventKey(String key) {
        return em.createQuery("SELECT n FROM Notification n WHERE n.eventKey = :k", Notification.class)
                .setParameter("k", key)
                .getResultList();
    }
}
