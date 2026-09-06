package com.paicli.change;

import java.io.IOException;
import java.util.Optional;

/** Imports one configured external work item into the ChangeWorkflow. */
public interface WorkItemAdapter {
    ChangeTaskId submit(String reference, String actorId, String actorType) throws IOException;

    /** Stable capability name exposed by the Change API. */
    String type();

    /** Fixed repository for remote imports, when the adapter is remotely configured. */
    default Optional<RepositoryRef> repository() { return Optional.empty(); }
}
