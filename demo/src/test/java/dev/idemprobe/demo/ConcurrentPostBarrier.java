package dev.idemprobe.demo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ConcurrentPostBarrier {

    private final AtomicReference<State> active = new AtomicReference<>();

    void arm(int expectedRequests) {
        if (!active.compareAndSet(null, new State(expectedRequests))) {
            throw new IllegalStateException("server barrier is already armed");
        }
    }

    boolean awaitAllEntered(long timeout, TimeUnit unit) throws InterruptedException {
        return requiredState().entered.await(timeout, unit);
    }

    int observedRequests() {
        State state = requiredState();
        return state.expectedRequests - (int) state.entered.getCount();
    }

    boolean enterAndAwaitRelease(long timeout, TimeUnit unit) throws InterruptedException {
        State state = active.get();
        if (state == null) {
            return true;
        }
        state.entered.countDown();
        return state.release.await(timeout, unit);
    }

    void release() {
        State state = active.get();
        if (state != null) {
            state.release.countDown();
        }
    }

    void disarm() {
        State state = active.getAndSet(null);
        if (state != null) {
            state.release.countDown();
        }
    }

    private State requiredState() {
        State state = active.get();
        if (state == null) {
            throw new IllegalStateException("server barrier is not armed");
        }
        return state;
    }

    private static final class State {

        private final int expectedRequests;
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);

        private State(int expectedRequests) {
            this.expectedRequests = expectedRequests;
            this.entered = new CountDownLatch(expectedRequests);
        }
    }
}
