package dev.idemprobe.config;

import java.util.Set;

public record AssertionSpec(Set<Integer> allowedStatuses, String sameValueAt) {
    public AssertionSpec {
        if (allowedStatuses == null) {
            throw new IllegalArgumentException("assertions.allowedStatuses must not be null");
        }
        if (allowedStatuses.stream().anyMatch(status -> status == null)) {
            throw new IllegalArgumentException("assertions.allowedStatuses must not contain null values");
        }
        allowedStatuses = Set.copyOf(allowedStatuses);
    }
}
