package com.example.insurance.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.OffsetDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class NotificationService {

    private static final Logger LOG = Logger.getLogger(NotificationService.class.getName());

    @Inject NotificationRepository                       repo;
    @Inject @RestClient NotificationDispatcher           dispatcher;

    /**
     * Persist + dispatch. The DB row is created PENDING up front so even a
     * total MI outage leaves an audit trail of every fan-in attempt. Status
     * flips to SENT or FAILED based on the dispatcher response — saved in
     * a separate transaction so a slow MI doesn't hold a Postgres tx open.
     */
    public void notify(String eventTopic, String eventKey, NotificationRequest req) {
        Notification n = createPending(eventTopic, eventKey, req);
        try (Response r = dispatcher.dispatch(req)) {
            if (r.getStatus() >= 200 && r.getStatus() < 300) {
                finalizeSent(n.getId(), "mi-" + r.getStatus());
            } else {
                String body = r.hasEntity() ? r.readEntity(String.class) : ("HTTP " + r.getStatus());
                if (body.length() > 250) body = body.substring(0, 250);
                finalizeFailed(n.getId(), body);
            }
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (reason.length() > 250) reason = reason.substring(0, 250);
            finalizeFailed(n.getId(), reason);
            LOG.log(Level.WARNING, "Notification dispatch threw for event " + eventTopic + "/" + eventKey, e);
        }
    }

    @Transactional
    Notification createPending(String topic, String key, NotificationRequest req) {
        Notification n = new Notification();
        n.setEventTopic(topic);
        n.setEventKey(key);
        n.setChannel(req.channel());
        n.setRecipient(req.recipient());
        n.setSubject(req.subject());
        n.setBody(req.body());
        n.setStatus("PENDING");
        n.setCreatedAt(OffsetDateTime.now());
        return repo.save(n);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Notification finalizeSent(Long id, String externalRef) {
        Notification n = repo.findById(id);
        n.setStatus("SENT");
        n.setExternalRef(externalRef);
        n.setDispatchedAt(OffsetDateTime.now());
        return repo.save(n);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Notification finalizeFailed(Long id, String reason) {
        Notification n = repo.findById(id);
        n.setStatus("FAILED");
        n.setFailureReason(reason);
        n.setDispatchedAt(OffsetDateTime.now());
        return repo.save(n);
    }
}
