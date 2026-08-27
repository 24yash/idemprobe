package dev.idemprobe.engine;

import java.time.Duration;

public record HttpInvocationResult(
        int index,
        InvocationPhase phase,
        int statusCode,
        String responseBody,
        Duration elapsed) implements InvocationResult {}
