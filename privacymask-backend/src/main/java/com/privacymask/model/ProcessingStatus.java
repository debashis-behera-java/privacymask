package com.privacymask.model;

/**
 * Lifecycle status of a gateway analyze request.
 */
public enum ProcessingStatus {

    /** Phase 2 legacy value: request accepted before detection existed. */
    RECEIVED,

    /** Phase 3: detection completed successfully (text still unmodified). */
    ANALYZED
}
