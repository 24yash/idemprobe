package dev.idemprobe.engine;

import dev.idemprobe.config.Scenario;
import dev.idemprobe.config.TargetSpec;
import dev.idemprobe.config.VerificationSpec;
import dev.idemprobe.http.ProbeHttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        invocations.addAll(runConcurrent(scenario, probeKey));

        Invocation verification = new Invocation(
                0,
                InvocationPhase.VERIFICATION,
                verificationRequest(scenario.verification()));
        return new ProbeExecution(invocations, httpClient.execute(verification));
    }

    private List<InvocationResult> runConcurrent(Scenario scenario, String probeKey) {
        int count = scenario.execution().concurrentDuplicates();
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<InvocationResult>> futures = new ArrayList<>(count);
        boolean completed = false;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutorCompletionService<InvocationResult> completions =
                    new ExecutorCompletionService<>(executor);
            try {
                for (int index = 0; index < count; index++) {
                    int invocationIndex = index;
                    futures.add(completions.submit(() -> {
                        ready.countDown();
                        start.await();
                        Invocation invocation = new Invocation(
                                invocationIndex,
                                InvocationPhase.CONCURRENT,
                                targetRequest(scenario.target(), probeKey));
                        return httpClient.execute(invocation);
                    }));
                }
                ready.await();
                start.countDown();
                List<InvocationResult> results = collectInIndexOrder(completions, count);
                completed = true;
                return results;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Concurrent probe execution interrupted", interrupted);
            } catch (ExecutionException failed) {
                throw new IllegalStateException("Concurrent probe execution failed", failed.getCause());
            } finally {
                start.countDown();
                if (!completed) {
                    futures.forEach(future -> future.cancel(true));
                }
            }
        }
    }

    private List<InvocationResult> collectInIndexOrder(
            ExecutorCompletionService<InvocationResult> completions, int count)
            throws InterruptedException, ExecutionException {
        List<InvocationResult> results = new ArrayList<>(count);
        for (int completed = 0; completed < count; completed++) {
            results.add(completions.take().get());
        }
        results.sort(Comparator.comparingInt(InvocationResult::index));
        return results;
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
