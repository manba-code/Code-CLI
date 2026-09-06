package com.paicli.change;

/** Aggregate-only control-plane projection; no requirements, actors, repository paths, or evidence are exposed. */
public record ChangeTaskMetrics(long total, long active, long failed, long completed,
                                long deliveryReview, long dispatchFailures) {
    public static ChangeTaskMetrics empty() { return new ChangeTaskMetrics(0, 0, 0, 0, 0, 0); }
}
