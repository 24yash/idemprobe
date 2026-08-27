package dev.idemprobe.config;

public record Scenario(
        TargetSpec target,
        ExecutionSpec execution,
        AssertionSpec assertions,
        VerificationSpec verification) {
}
