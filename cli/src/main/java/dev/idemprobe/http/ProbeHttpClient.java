package dev.idemprobe.http;

import dev.idemprobe.engine.Invocation;
import dev.idemprobe.engine.InvocationResult;

public interface ProbeHttpClient {
    InvocationResult execute(Invocation invocation);
}
