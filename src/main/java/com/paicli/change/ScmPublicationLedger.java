package com.paicli.change;

import java.util.List;
import java.util.Optional;

/** Durable publication-intent/result ledger used after remote SCM reconciliation. */
interface ScmPublicationLedger extends AutoCloseable {
    void requireCurrentVersion(ChangeTask task);
    DeliveryRef save(ChangeTask task, String conclusion, String key, String deliveryId, String deliveryUrl);
    Optional<DeliveryRef> byKey(String key);
    List<DeliveryRef> history(ChangeTaskId id);
    @Override void close();
}
