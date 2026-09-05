package com.paicli.change;

import java.util.List;
import java.util.Objects;

public record ChangeTaskView(ChangeTask task, List<ChangeEvent> events, DeliveryRef delivery, List<DeliveryRef> deliveryHistory) {
    public ChangeTaskView(ChangeTask task, List<ChangeEvent> events, DeliveryRef delivery) {
        this(task, events, delivery, List.of());
    }
    public ChangeTaskView(ChangeTask task, List<ChangeEvent> events) {
        this(task, events, null);
    }
    public ChangeTaskView {
        task = Objects.requireNonNull(task, "task");
        deliveryHistory = deliveryHistory == null ? List.of() : List.copyOf(deliveryHistory);
        events = events == null ? List.of() : List.copyOf(events);
    }
}
