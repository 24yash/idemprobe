package dev.idemprobe.engine;

import dev.idemprobe.config.Scenario;
import dev.idemprobe.config.TargetSpec;
import dev.idemprobe.config.VerificationSpec;
import dev.idemprobe.http.ProbeHttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProbeRunner {
    private static final String PROBE_KEY_TOKEN = "${probe.key}";

    private final ProbeHttpClient httpClient;

    public ProbeRunner(ProbeHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public ProbeExecution run(Scenario scenario, String probeKey) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(probeKey, "probeKey");

        List<InvocationResult> invocations = new ArrayList<>();
        for (int index = 0; index < scenario.execution().sequentialDuplicates(); index++) {
            Invocation invocation = new Invocation(
                    index,
                    InvocationPhase.SEQUENTIAL,
                    targetRequest(scenario.target(), probeKey));
            invocations.add(httpClient.execute(invocation));
        }

        Invocation verification = new Invocation(
                0,
                InvocationPhase.VERIFICATION,
                verificationRequest(scenario.verification()));
        return new ProbeExecution(invocations, httpClient.execute(verification));
    }

    private HttpRequest targetRequest(TargetSpec target, String probeKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(target.url());
        target.headers().forEach((name, value) ->
                request.header(name, value.replace(PROBE_KEY_TOKEN, probeKey)));
        HttpRequest.BodyPublisher body = target.body() == null
                ? BodyPublishers.noBody()
                : BodyPublishers.ofString(target.body());
        return request.method(target.method(), body).build();
    }

    private HttpRequest verificationRequest(VerificationSpec verification) {
        return HttpRequest.newBuilder(verification.url())
                .method(verification.method(), BodyPublishers.noBody())
                .build();
    }
}
