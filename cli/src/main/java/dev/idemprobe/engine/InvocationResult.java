package dev.idemprobe.engine;

import java.time.Duration;

public sealed interface InvocationResult permits HttpInvocationResult, TransportFailure {
    int index();

    InvocationPhase phase();

    Duration elapsed();
}
