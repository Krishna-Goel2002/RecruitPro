package com.recruitpro.application.service;

import com.recruitpro.application.entity.Application;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Calls every {@link CandidateDepartureListener} bean in {@code @Order} (R2-M01). */
@Component
public class CandidateDepartures {

    private final ObjectProvider<CandidateDepartureListener> listeners;

    public CandidateDepartures(ObjectProvider<CandidateDepartureListener> listeners) {
        this.listeners = listeners;
    }

    public void candidateLeft(Application application, boolean accountDeleted) {
        if (application == null || application.getId() == null) {
            return;
        }
        listeners.orderedStream().forEach(listener -> listener.candidateLeft(application, accountDeleted));
    }
}
