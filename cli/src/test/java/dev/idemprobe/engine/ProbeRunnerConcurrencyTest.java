package dev.idemprobe.engine;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.idemprobe.config.AssertionSpec;
import dev.idemprobe.config.ExecutionSpec;
import dev.idemprobe.config.Scenario;
import dev.idemprobe.config.TargetSpec;
import dev.idemprobe.config.VerificationSpec;
import dev.idemprobe.http.ProbeHttpClient;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = SECONDS)
class ProbeRunnerConcurrencyTest {

    @Test
    void overlapsEveryConcurrentInvocationBeforeAnyMayComplete() throws Exception {
        int concurrentDuplicates = 20;
        OverlapProbeHttpClient client = new OverlapProbeHttpClient(concurrentDuplicates);
        FinalWorkerGate workerGate = new FinalWorkerGate(concurrentDuplicates);
        AtomicReference<ProbeExecution> execution = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread runnerThread = Thread.startVirtualThread(() -> {
            try {
                execution.set(new ProbeRunner(client, workerGate)
                        .run(scenario(0, concurrentDuplicates), "shared-key"));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });

        try {
            assertThat(workerGate.finalWorkerWaiting().await(2, SECONDS)).isTrue();
            assertThat(client.concurrentExecutions()).isZero();
            workerGate.releaseFinalWorker();
            assertThat(client.allStarted().await(2, SECONDS)).isTrue();
            assertThat(client.maxInFlight()).isEqualTo(concurrentDuplicates);
        } finally {
            cleanupRunner(
                    runnerThread, workerGate::releaseFinalWorker, client::releaseResponses);
        }

        assertThat(failure.get()).isNull();
        assertThat(execution.get().invocations()).hasSize(concurrentDuplicates);
    }

    @Test
    void interruptionBeforeStartCancelsWorkersWithoutExecutingRequests() throws Exception {
        int concurrentDuplicates = 20;
        OverlapProbeHttpClient client = new OverlapProbeHttpClient(concurrentDuplicates);
        FinalWorkerGate workerGate = new FinalWorkerGate(concurrentDuplicates);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        CountDownLatch runnerFinished = new CountDownLatch(1);
        Thread runnerThread = Thread.startVirtualThread(() -> {
            try {
                new ProbeRunner(client, workerGate)
                        .run(scenario(0, concurrentDuplicates), "shared-key");
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
                runnerFinished.countDown();
            }
        });

        try {
            assertThat(workerGate.finalWorkerWaiting().await(2, SECONDS)).isTrue();
            runnerThread.interrupt();
            assertThat(runnerFinished.await(2, SECONDS)).isTrue();
        } finally {
            cleanupRunner(
                    runnerThread, workerGate::releaseFinalWorker, client::releaseResponses);
        }

        assertThat(client.concurrentExecutions()).isZero();
        assertThat(client.verificationCalled()).isFalse();
        assertThat(failure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Concurrent probe execution interrupted")
                .cause()
                .isInstanceOf(InterruptedException.class);
        assertThat(interruptPreserved).isTrue();
    }

    @Test
    void preservesInvocationOrderWhenConcurrentResponsesCompleteInReverse() throws Exception {
        int concurrentDuplicates = 5;
        ReverseCompletionProbeHttpClient client =
                new ReverseCompletionProbeHttpClient(concurrentDuplicates);
        AtomicReference<ProbeExecution> execution = new AtomicReference<>();
        Thread runnerThread = Thread.startVirtualThread(() ->
                execution.set(new ProbeRunner(client).run(scenario(2, concurrentDuplicates), "shared-key")));

        try {
            assertThat(client.allStarted().await(2, SECONDS)).isTrue();
            for (int index = concurrentDuplicates - 1; index >= 0; index--) {
                client.complete(index);
            }
            runnerThread.join(2_000);
        } finally {
            cleanupRunner(runnerThread, client::releaseAll);
        }

        assertThat(client.completionOrder()).containsExactly(4, 3, 2, 1, 0);
        assertThat(execution.get().invocations())
                .extracting(InvocationResult::phase)
                .containsExactly(
                        InvocationPhase.SEQUENTIAL,
                        InvocationPhase.SEQUENTIAL,
                        InvocationPhase.CONCURRENT,
                        InvocationPhase.CONCURRENT,
                        InvocationPhase.CONCURRENT,
                        InvocationPhase.CONCURRENT,
                        InvocationPhase.CONCURRENT);
        assertThat(execution.get().invocations())
                .extracting(InvocationResult::index)
                .containsExactly(0, 1, 0, 1, 2, 3, 4);
    }

    @Test
    void reusesOneProbeKeyAcrossSequentialAndConcurrentPhases() {
        Queue<String> keys = new ConcurrentLinkedQueue<>();
        ProbeHttpClient client = invocation -> {
            if (invocation.phase() != InvocationPhase.VERIFICATION) {
                keys.add(invocation.request().headers().firstValue("Idempotency-Key").orElseThrow());
            }
            return result(invocation);
        };

        new ProbeRunner(client).run(scenario(2, 3), "shared-key");

        assertThat(keys).hasSize(5).containsOnly("shared-key");
    }

    @Test
    void runsVerificationOnlyAfterBothDuplicatePhasesComplete() {
        AtomicInteger completedTargets = new AtomicInteger();
        AtomicInteger targetsSeenAtVerification = new AtomicInteger(-1);
        AtomicBoolean verificationCalled = new AtomicBoolean();
        ProbeHttpClient client = invocation -> {
            if (invocation.phase() == InvocationPhase.VERIFICATION) {
                verificationCalled.set(true);
                targetsSeenAtVerification.set(completedTargets.get());
            } else {
                completedTargets.incrementAndGet();
            }
            return result(invocation);
        };

        ProbeExecution execution = new ProbeRunner(client).run(scenario(2, 3), "shared-key");

        assertThat(verificationCalled).isTrue();
        assertThat(targetsSeenAtVerification).hasValue(5);
        assertThat(execution.verificationResult().phase()).isEqualTo(InvocationPhase.VERIFICATION);
    }

    @Test
    void cancelsConcurrentPeersAndSkipsVerificationWhenOneInvocationFails() throws Exception {
        int concurrentDuplicates = 5;
        FailingProbeHttpClient client = new FailingProbeHttpClient(concurrentDuplicates);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread runnerThread = Thread.startVirtualThread(() -> {
            try {
                new ProbeRunner(client).run(scenario(0, concurrentDuplicates), "shared-key");
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });

        try {
            assertThat(client.allStarted().await(2, SECONDS)).isTrue();
            client.triggerFailure();
            runnerThread.join(2_000);
        } finally {
            cleanupRunner(runnerThread, client::triggerFailure, client::releasePeers);
        }

        assertThat(failure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Concurrent probe execution failed")
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        assertThat(client.interruptedPeers().await(2, SECONDS)).isTrue();
        assertThat(client.verificationCalled()).isFalse();
    }

    @Test
    void restoresCallerInterruptAndCancelsConcurrentTasks() throws Exception {
        int concurrentDuplicates = 5;
        InterruptibleProbeHttpClient client = new InterruptibleProbeHttpClient(concurrentDuplicates);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread runnerThread = Thread.startVirtualThread(() -> {
            try {
                new ProbeRunner(client).run(scenario(0, concurrentDuplicates), "shared-key");
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });

        try {
            assertThat(client.allStarted().await(2, SECONDS)).isTrue();
            runnerThread.interrupt();
            runnerThread.join(2_000);
        } finally {
            cleanupRunner(runnerThread, client::releaseTasks);
        }

        assertThat(failure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Concurrent probe execution interrupted")
                .cause()
                .isInstanceOf(InterruptedException.class);
        assertThat(interruptPreserved).isTrue();
        assertThat(client.interruptedTasks().await(2, SECONDS)).isTrue();
        assertThat(client.verificationCalled()).isFalse();
    }

    private Scenario scenario(int sequentialDuplicates, int concurrentDuplicates) {
        return new Scenario(
                new TargetSpec(
                        "POST",
                        URI.create("http://localhost:8080/reservations"),
                        Map.of("Idempotency-Key", "${probe.key}"),
                        "{}"),
                new ExecutionSpec(sequentialDuplicates, concurrentDuplicates),
                new AssertionSpec(Set.of(200, 201), "$.reservationId"),
                new VerificationSpec(
                        "GET",
                        URI.create("http://localhost:8080/reservations/count"),
                        "$.count",
                        BigDecimal.ONE));
    }

    private static final class OverlapProbeHttpClient implements ProbeHttpClient {
        private final CountDownLatch allStarted;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final AtomicInteger concurrentExecutions = new AtomicInteger();
        private final AtomicBoolean verificationCalled = new AtomicBoolean();

        private OverlapProbeHttpClient(int count) {
            allStarted = new CountDownLatch(count);
        }

        @Override
        public InvocationResult execute(Invocation invocation) {
            if (invocation.phase() == InvocationPhase.VERIFICATION) {
                verificationCalled.set(true);
                return result(invocation);
            }
            if (invocation.phase() != InvocationPhase.CONCURRENT) {
                return result(invocation);
            }

            concurrentExecutions.incrementAndGet();
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            allStarted.countDown();
            try {
                release.await();
                return result(invocation);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new TransportFailure(
                        invocation.index(), invocation.phase(), "interrupted", Duration.ZERO);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        private CountDownLatch allStarted() {
            return allStarted;
        }

        private void releaseResponses() {
            release.countDown();
        }

        private int maxInFlight() {
            return maxInFlight.get();
        }

        private int concurrentExecutions() {
            return concurrentExecutions.get();
        }

        private boolean verificationCalled() {
            return verificationCalled.get();
        }
    }

    private static final class FinalWorkerGate implements ProbeRunner.WorkerStartCoordinator {
        private final int finalIndex;
        private final CountDownLatch otherWorkersArrived;
        private final CountDownLatch finalWorkerWaiting = new CountDownLatch(1);
        private final CountDownLatch releaseFinalWorker = new CountDownLatch(1);

        private FinalWorkerGate(int count) {
            finalIndex = count - 1;
            otherWorkersArrived = new CountDownLatch(count - 1);
        }

        @Override
        public void beforeReady(int invocationIndex) throws InterruptedException {
            if (invocationIndex == finalIndex) {
                otherWorkersArrived.await();
                finalWorkerWaiting.countDown();
                releaseFinalWorker.await();
            } else {
                otherWorkersArrived.countDown();
            }
        }

        private CountDownLatch finalWorkerWaiting() {
            return finalWorkerWaiting;
        }

        private void releaseFinalWorker() {
            releaseFinalWorker.countDown();
        }
    }

    private static final class ReverseCompletionProbeHttpClient implements ProbeHttpClient {
        private final CountDownLatch allStarted;
        private final List<CountDownLatch> releaseByIndex;
        private final List<CountDownLatch> completedByIndex;
        private final List<Integer> completionOrder = new CopyOnWriteArrayList<>();

        private ReverseCompletionProbeHttpClient(int count) {
            allStarted = new CountDownLatch(count);
            releaseByIndex = latches(count);
            completedByIndex = latches(count);
        }

        @Override
        public InvocationResult execute(Invocation invocation) {
            if (invocation.phase() != InvocationPhase.CONCURRENT) {
                return result(invocation);
            }

            allStarted.countDown();
            try {
                releaseByIndex.get(invocation.index()).await();
                completionOrder.add(invocation.index());
                completedByIndex.get(invocation.index()).countDown();
                return result(invocation);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new TransportFailure(
                        invocation.index(), invocation.phase(), "interrupted", Duration.ZERO);
            }
        }

        private void complete(int index) throws InterruptedException {
            releaseByIndex.get(index).countDown();
            assertThat(completedByIndex.get(index).await(2, SECONDS)).isTrue();
        }

        private void releaseAll() {
            releaseByIndex.forEach(CountDownLatch::countDown);
        }

        private CountDownLatch allStarted() {
            return allStarted;
        }

        private List<Integer> completionOrder() {
            return completionOrder;
        }

        private static List<CountDownLatch> latches(int count) {
            List<CountDownLatch> latches = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                latches.add(new CountDownLatch(1));
            }
            return latches;
        }
    }

    private static final class FailingProbeHttpClient implements ProbeHttpClient {
        private final CountDownLatch allStarted;
        private final CountDownLatch triggerFailure = new CountDownLatch(1);
        private final CountDownLatch waitForCancellation = new CountDownLatch(1);
        private final CountDownLatch interruptedPeers;
        private final AtomicBoolean verificationCalled = new AtomicBoolean();
        private final int failingIndex;

        private FailingProbeHttpClient(int count) {
            allStarted = new CountDownLatch(count);
            interruptedPeers = new CountDownLatch(count - 1);
            failingIndex = count - 1;
        }

        @Override
        public InvocationResult execute(Invocation invocation) {
            if (invocation.phase() == InvocationPhase.VERIFICATION) {
                verificationCalled.set(true);
                return result(invocation);
            }
            if (invocation.phase() != InvocationPhase.CONCURRENT) {
                return result(invocation);
            }

            allStarted.countDown();
            try {
                triggerFailure.await();
                if (invocation.index() == failingIndex) {
                    throw new IllegalStateException("boom");
                }
                waitForCancellation.await();
                return result(invocation);
            } catch (InterruptedException interrupted) {
                interruptedPeers.countDown();
                Thread.currentThread().interrupt();
                return new TransportFailure(
                        invocation.index(), invocation.phase(), "interrupted", Duration.ZERO);
            }
        }

        private CountDownLatch allStarted() {
            return allStarted;
        }

        private void triggerFailure() {
            triggerFailure.countDown();
        }

        private void releasePeers() {
            waitForCancellation.countDown();
        }

        private CountDownLatch interruptedPeers() {
            return interruptedPeers;
        }

        private boolean verificationCalled() {
            return verificationCalled.get();
        }
    }

    private static final class InterruptibleProbeHttpClient implements ProbeHttpClient {
        private final CountDownLatch allStarted;
        private final CountDownLatch waitForCancellation = new CountDownLatch(1);
        private final CountDownLatch interruptedTasks;
        private final AtomicBoolean verificationCalled = new AtomicBoolean();

        private InterruptibleProbeHttpClient(int count) {
            allStarted = new CountDownLatch(count);
            interruptedTasks = new CountDownLatch(count);
        }

        @Override
        public InvocationResult execute(Invocation invocation) {
            if (invocation.phase() == InvocationPhase.VERIFICATION) {
                verificationCalled.set(true);
                return result(invocation);
            }
            if (invocation.phase() != InvocationPhase.CONCURRENT) {
                return result(invocation);
            }

            allStarted.countDown();
            try {
                waitForCancellation.await();
                return result(invocation);
            } catch (InterruptedException interrupted) {
                interruptedTasks.countDown();
                Thread.currentThread().interrupt();
                return new TransportFailure(
                        invocation.index(), invocation.phase(), "interrupted", Duration.ZERO);
            }
        }

        private CountDownLatch allStarted() {
            return allStarted;
        }

        private CountDownLatch interruptedTasks() {
            return interruptedTasks;
        }

        private void releaseTasks() {
            waitForCancellation.countDown();
        }

        private boolean verificationCalled() {
            return verificationCalled.get();
        }
    }

    private static HttpInvocationResult result(Invocation invocation) {
        String body = invocation.phase() == InvocationPhase.VERIFICATION
                ? "{\"count\":1}"
                : "{\"reservationId\":\"r-1\"}";
        return new HttpInvocationResult(
                invocation.index(), invocation.phase(), 201, body, Duration.ofMillis(10));
    }

    private static void cleanupRunner(Thread runnerThread, Runnable... releases)
            throws InterruptedException {
        for (Runnable release : releases) {
            release.run();
        }
        runnerThread.join(2_000);
        if (runnerThread.isAlive()) {
            runnerThread.interrupt();
        }
        runnerThread.join(2_000);
        assertThat(runnerThread.isAlive()).isFalse();
    }
}
