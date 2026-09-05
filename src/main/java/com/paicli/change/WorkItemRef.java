package com.paicli.change;

import java.util.List;

public record WorkItemRef(
        String type,
        String externalId,
        String url,
        List<String> labels,
        String priority
) {
    public WorkItemRef {
        type = normalize(type);
        externalId = normalize(externalId);
        url = normalize(url);
        labels = labels == null
                ? List.of()
                : labels.stream().map(WorkItemRef::normalize).filter(value -> !value.isEmpty()).distinct().toList();
        priority = normalize(priority);
    }

    public WorkItemRef(String type, String externalId, String url) {
        this(type, externalId, url, List.of(), "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
