package com.paicli.change;

import java.util.List;
import java.util.Optional;

/** Minimal SCM publication boundary. ChangeWorkflow alone decides eligibility and conclusion. */
public interface ScmAdapter extends AutoCloseable {
    String publicationKey(ChangeTask task);

    DeliveryRef publish(ChangeTask task, String conclusion);

    Optional<DeliveryRef> find(ChangeTask task);

    List<DeliveryRef> history(ChangeTaskId id);

    /** Stable actor/capability name; must never contain credentials. */
    String type();

    /** Read-only dependency probe. It must never create or update remote SCM state. */
    default void checkHealth() { }

    @Override
    void close() throws Exception;
}
