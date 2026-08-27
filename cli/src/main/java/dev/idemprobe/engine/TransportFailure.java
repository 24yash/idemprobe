package dev.idemprobe.engine;

import java.time.Duration;

public record TransportFailure(
        int index,
        InvocationPhase phase,
        String message,
        Duration elapsed) implements InvocationResult {}
