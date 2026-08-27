package dev.idemprobe.engine;

import java.net.http.HttpRequest;

public record Invocation(int index, InvocationPhase phase, HttpRequest request) {}
