package com.paicli.change;

/**
 * Durable control-plane persistence seam. A write of a ChangeTask and its event is atomic;
 * approval and policy writes keep their corresponding audit event in the same transaction.
 */
public interface ChangePersistence extends ChangeStore, ChangeEventStore, ToolGovernanceStore, AutoCloseable {
    String backend();

    int schemaVersion();

    void checkHealth();

    ChangeTaskMetrics metrics();

    @Override
    void close();
}
