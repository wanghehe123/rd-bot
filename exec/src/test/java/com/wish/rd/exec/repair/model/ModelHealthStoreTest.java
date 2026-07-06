package com.wish.rd.exec.repair.model;

import com.wish.rd.exec.repair.health.ModelHealthStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelHealthStoreTest {

    @Test
    void shouldAllowClosedModelByDefault() {
        ModelHealthStore store = store();

        assertTrue(store.allowCall("deepseek"));
        assertFalse(store.isUnavailable("deepseek"));
        assertEquals(ModelHealthState.CLOSED, store.snapshot("deepseek").state());
    }

    @Test
    void shouldOpenAfterFailureThreshold() {
        ModelHealthStore store = store();

        store.markFailure("deepseek");
        assertTrue(store.allowCall("deepseek"));

        store.markFailure("deepseek");

        assertFalse(store.allowCall("deepseek"));
        assertTrue(store.isUnavailable("deepseek"));
        assertEquals(ModelHealthState.OPEN, store.snapshot("deepseek").state());
    }

    @Test
    void shouldMoveOpenToHalfOpenAfterOpenDuration() throws InterruptedException {
        ModelHealthStore store = store();
        open(store, "deepseek");

        Thread.sleep(75L);

        assertTrue(store.allowCall("deepseek"));
        ModelHealthSnapshot snapshot = store.snapshot("deepseek");
        assertEquals(ModelHealthState.HALF_OPEN, snapshot.state());
        assertTrue(snapshot.halfOpenInFlight());
    }

    @Test
    void shouldAllowOnlyOneHalfOpenCallInFlight() throws InterruptedException {
        ModelHealthStore store = store();
        open(store, "deepseek");

        Thread.sleep(75L);

        assertTrue(store.allowCall("deepseek"));
        assertFalse(store.allowCall("deepseek"));
        assertTrue(store.isUnavailable("deepseek"));
    }

    @Test
    void shouldCloseCircuitAfterSuccess() throws InterruptedException {
        ModelHealthStore store = store();
        open(store, "deepseek");
        Thread.sleep(75L);
        assertTrue(store.allowCall("deepseek"));

        store.markSuccess("deepseek");

        ModelHealthSnapshot snapshot = store.snapshot("deepseek");
        assertEquals(ModelHealthState.CLOSED, snapshot.state());
        assertEquals(0, snapshot.consecutiveFailures());
        assertFalse(snapshot.halfOpenInFlight());
        assertTrue(store.allowCall("deepseek"));
    }

    @Test
    void shouldReopenCircuitWhenHalfOpenCallFails() throws InterruptedException {
        ModelHealthStore store = store();
        open(store, "deepseek");
        Thread.sleep(75L);
        assertTrue(store.allowCall("deepseek"));

        store.markFailure("deepseek");

        assertEquals(ModelHealthState.OPEN, store.snapshot("deepseek").state());
        assertFalse(store.allowCall("deepseek"));
    }

    @Test
    void shouldReturnUnavailableForOpenAndBusyHalfOpenStates() throws InterruptedException {
        ModelHealthStore store = store();
        open(store, "deepseek");

        assertTrue(store.isUnavailable("deepseek"));

        Thread.sleep(75L);
        assertTrue(store.allowCall("deepseek"));
        assertTrue(store.isUnavailable("deepseek"));
    }

    @Test
    void shouldBypassStateWhenCircuitBreakerDisabled() {
        ModelHealthStore store = new ModelHealthStore(ModelCircuitBreakerPolicy.disabled());

        store.markFailure("deepseek");
        store.markFailure("deepseek");

        assertTrue(store.allowCall("deepseek"));
        assertFalse(store.isUnavailable("deepseek"));
        assertTrue(store.snapshots().isEmpty());
    }

    private static ModelHealthStore store() {
        return new ModelHealthStore(new ModelCircuitBreakerPolicy(true, 2, 50L));
    }

    private static void open(ModelHealthStore store, String id) {
        store.markFailure(id);
        store.markFailure(id);
    }
}
