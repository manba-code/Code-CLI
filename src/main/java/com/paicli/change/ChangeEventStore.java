package com.paicli.change;

import java.util.List;

public interface ChangeEventStore {
    List<ChangeEvent> events(ChangeTaskId id);
}
