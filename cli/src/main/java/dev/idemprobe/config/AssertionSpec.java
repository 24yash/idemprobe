package dev.idemprobe.config;

import java.util.Set;

public record AssertionSpec(Set<Integer> allowedStatuses, String sameValueAt) {
    public AssertionSpec {
        allowedStatuses = Set.copyOf(allowedStatuses);
    }
}
