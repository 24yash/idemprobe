package dev.idemprobe.engine;

import java.util.List;
import java.util.Objects;

public record ProbeExecution(
        List<InvocationResult> invocations,
        InvocationResult verificationResult) {
    public ProbeExecution {
        invocations = List.copyOf(invocations);
        verificationResult = Objects.requireNonNull(verificationResult, "verificationResult");
    }
}
