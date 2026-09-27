package com.privacymask.detection;

/**
 * PII categories the Phase 3 regex engine can detect.
 *
 * <p>Each type carries a {@code priority} used for deterministic overlap
 * resolution: when two candidates overlap, the lower value (more specific
 * shape) wins. {@code PERSON_NAME} is deliberately absent - regex cannot
 * reliably identify arbitrary human names; it is reserved for a future NLP
 * detector.</p>
 */
public enum PiiType {

    URL(10),
    EMAIL(20),
    IP_ADDRESS(30),
    CREDIT_CARD(40),
    SSN(50),
    PHONE(60);

    private final int priority;

    PiiType(int priority) {
        this.priority = priority;
    }

    /**
     * Overlap precedence. Lower wins (more specific pattern beats a generic
     * numeric match, e.g. a card number is never also reported as a phone).
     */
    public int priority() {
        return priority;
    }
}
