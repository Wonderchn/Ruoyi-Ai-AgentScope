package com.nageoffer.ai.ragent.coding.event;

import com.nageoffer.ai.ragent.coding.model.HarnessEvent;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.store.HarnessStore;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Durable-first event publisher with replay + non-blocking live delivery.
 *
 * <p>Simplified from the legacy HarnessEventHub: bounded per-subscription queues, replay from the
 * durable ledger before live events, one terminal event per run, and slow-subscriber disconnects.
 * The worker never blocks on SSE. Callback timeouts and multi-level subscriber quotas are not
 * ported (documented simplification).
 */
public class HarnessEventHub {

    private static final int REPLAY_PAGE_SIZE = 1_000;
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 1_024;

    private final HarnessStore store;
    private final Executor deliveryExecutor;
    private final ConcurrentMap<RunKey, CopyOnWriteArrayList<Subscription>> subscribers =
            new ConcurrentHashMap<>();
    private final Object[] runLocks = createLocks(256);

    public HarnessEventHub(HarnessStore store, Executor deliveryExecutor) {
        this.store = Objects.requireNonNull(store, "store");
        this.deliveryExecutor = Objects.requireNonNull(deliveryExecutor, "deliveryExecutor");
    }

    public HarnessEvent publish(HarnessOwner owner, HarnessEvent draft) {
        Objects.requireNonNull(draft, "draft");
        RunKey key = RunKey.of(owner, draft.sessionId(), draft.runId());
        if (!draft.isTerminalType()) {
            HarnessEvent durable = store.appendEvent(owner, draft);
            fanout(key, durable);
            return durable;
        }
        synchronized (runLock(key)) {
            HarnessRunState run = store.findRun(owner, key.sessionId(), key.runId()).orElse(null);
            if (run == null) {
                throw new IllegalStateException("Terminal event has no durable run");
            }
            HarnessEvent existing = findUniqueTerminal(owner, key);
            if (existing != null) {
                requireCompatible(existing, draft);
                return existing;
            }
            HarnessEvent durable = store.appendEvent(owner, draft);
            fanout(key, durable);
            return durable;
        }
    }

    /** Publishes a stable draft exactly once by its event id. */
    public HarnessEvent publishIdempotent(HarnessOwner owner, HarnessEvent draft) {
        Objects.requireNonNull(draft, "draft");
        RunKey key = RunKey.of(owner, draft.sessionId(), draft.runId());
        synchronized (runLock(key)) {
            HarnessEvent existing = findById(owner, key, draft.eventId());
            if (existing != null) {
                requireSameLogical(existing, draft);
                return existing;
            }
            if (draft.isTerminalType()) {
                HarnessEvent terminal = findUniqueTerminal(owner, key);
                if (terminal != null) {
                    requireCompatible(terminal, draft);
                    return terminal;
                }
            }
            return publish(owner, draft);
        }
    }

    public HarnessEventSubscription subscribe(HarnessOwner owner, String sessionId, String runId,
                                              long afterSequence,
                                              Consumer<HarnessEvent> consumer,
                                              Consumer<Throwable> errorConsumer) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(errorConsumer, "errorConsumer");
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence cannot be negative");
        }
        RunKey key = RunKey.of(owner, sessionId, runId);
        if (store.findRun(owner, sessionId, runId).isEmpty()) {
            throw new IllegalArgumentException("Unknown run: " + runId);
        }
        // C2：终态落盘后、终态事件发布前崩溃时，订阅方也必须看到终态，而不是轮询到 emitter 超时。
        ensureTerminalEvent(owner, sessionId, runId);
        Subscription subscription = new Subscription(key, owner, afterSequence, consumer,
                errorConsumer);
        try {
            admit(subscription);
            deliveryExecutor.execute(subscription::replayThenDrain);
        } catch (RuntimeException admissionFailure) {
            subscription.close();
            throw admissionFailure;
        }
        return subscription;
    }

    /** Repairs the state-to-event crash window for SSE and polling callers alike. */
    public void ensureTerminalEvent(HarnessOwner owner, String sessionId, String runId) {
        RunKey key = RunKey.of(owner, sessionId, runId);
        synchronized (runLock(key)) {
            HarnessRunState run = store.findRun(owner, sessionId, runId).orElse(null);
            if (run == null || !run.status().isTerminal()) {
                return;
            }
            if (findUniqueTerminal(owner, key) != null) {
                return;
            }
            String type = switch (run.status()) {
                case COMPLETED -> "run.completed";
                case FAILED -> "run.failed";
                case CANCELLED -> "run.cancelled";
                default -> null;
            };
            if (type == null) {
                return;
            }
            publishIdempotent(owner, HarnessEvent.draftWithId(
                    "run-terminal-repair:" + run.runId() + ":" + type,
                    sessionId, runId, type, null, null, null,
                    Map.of("status", run.status().name(), "revision", run.revision(),
                            "code", "TERMINAL_STATE_RECOVERED"), run.updatedAt()));
        }
    }

    private void admit(Subscription subscription) {
        subscribers.computeIfAbsent(subscription.key,
                ignored -> new CopyOnWriteArrayList<>()).add(subscription);
    }

    private void remove(Subscription subscription) {
        CopyOnWriteArrayList<Subscription> list = subscribers.get(subscription.key);
        if (list != null && list.remove(subscription) && list.isEmpty()) {
            subscribers.remove(subscription.key, list);
        }
    }

    private void fanout(RunKey key, HarnessEvent event) {
        List<Subscription> current = subscribers.get(key);
        if (current != null) {
            for (Subscription subscriber : current) {
                subscriber.offer(event);
            }
        }
    }

    private HarnessEvent findById(HarnessOwner owner, RunKey key, String eventId) {
        HarnessEvent found = null;
        long cursor = 0;
        while (true) {
            List<HarnessEvent> page = store.readEvents(owner, key.sessionId(), key.runId(),
                    cursor, REPLAY_PAGE_SIZE);
            for (HarnessEvent event : page) {
                if (eventId.equals(event.eventId())) {
                    if (found != null) {
                        throw new IllegalStateException("Duplicate stable Harness event id: " + eventId);
                    }
                    found = event;
                }
                cursor = Math.max(cursor, event.sequence());
            }
            if (page.size() < REPLAY_PAGE_SIZE) {
                return found;
            }
        }
    }

    private HarnessEvent findUniqueTerminal(HarnessOwner owner, RunKey key) {
        HarnessEvent terminal = null;
        long cursor = 0;
        while (true) {
            List<HarnessEvent> page = store.readEvents(owner, key.sessionId(), key.runId(),
                    cursor, REPLAY_PAGE_SIZE);
            for (HarnessEvent event : page) {
                if (event.isTerminalType()) {
                    if (terminal != null) {
                        throw new IllegalStateException(
                                "Harness run has more than one terminal event sequence");
                    }
                    terminal = event;
                }
                cursor = Math.max(cursor, event.sequence());
            }
            if (page.size() < REPLAY_PAGE_SIZE) {
                return terminal;
            }
        }
    }

    private void requireSameLogical(HarnessEvent durable, HarnessEvent draft) {
        if (durable.schemaVersion() != draft.schemaVersion()
                || !durable.eventId().equals(draft.eventId())
                || !durable.sessionId().equals(draft.sessionId())
                || !durable.runId().equals(draft.runId())
                || durable.timestamp() != draft.timestamp()
                || !durable.type().equals(draft.type())
                || !Objects.equals(durable.stepId(), draft.stepId())
                || !Objects.equals(durable.toolCallId(), draft.toolCallId())
                || !Objects.equals(durable.approvalId(), draft.approvalId())
                || !logicalEquals(durable.data(), draft.data())) {
            throw new IllegalStateException(
                    "Harness event id is bound to a different payload: " + draft.eventId());
        }
    }

    private void requireCompatible(HarnessEvent durable, HarnessEvent draft) {
        if (durable.schemaVersion() != draft.schemaVersion()
                || !durable.sessionId().equals(draft.sessionId())
                || !durable.runId().equals(draft.runId())
                || !durable.type().equals(draft.type())
                || !logicalEquals(durable.data(), draft.data())) {
            throw new IllegalStateException(
                    "Terminal event type is bound to a different payload: " + draft.type());
        }
    }

    private boolean logicalEquals(Object durable, Object draft) {
        if (Objects.equals(durable, draft)) {
            return true;
        }
        if (durable instanceof Number left && draft instanceof Number right) {
            BigInteger a = left instanceof BigInteger big ? big : BigInteger.valueOf(left.longValue());
            BigInteger b = right instanceof BigInteger big ? big : BigInteger.valueOf(right.longValue());
            return a.equals(b);
        }
        if (durable instanceof Map<?, ?> left && draft instanceof Map<?, ?> right) {
            if (!left.keySet().equals(right.keySet())) {
                return false;
            }
            for (Object key : left.keySet()) {
                if (!logicalEquals(left.get(key), right.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (durable instanceof List<?> left && draft instanceof List<?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!logicalEquals(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private Object runLock(RunKey key) {
        return runLocks[Math.floorMod(key.hashCode(), runLocks.length)];
    }

    private static Object[] createLocks(int count) {
        Object[] locks = new Object[count];
        for (int index = 0; index < count; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private record RunKey(String tenantId, Long userId, String sessionId, String runId) {
        private static RunKey of(HarnessOwner owner, String sessionId, String runId) {
            return new RunKey(owner.tenantId(), owner.userId(), sessionId, runId);
        }
    }

    private final class Subscription implements HarnessEventSubscription {
        private final RunKey key;
        private final HarnessOwner owner;
        private final Consumer<HarnessEvent> consumer;
        private final Consumer<Throwable> errorConsumer;
        private final ArrayBlockingQueue<HarnessEvent> queue =
                new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE_CAPACITY);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private long cursor;
        private long lastTerminalRepair = System.currentTimeMillis();

        private Subscription(RunKey key, HarnessOwner owner, long afterSequence,
                             Consumer<HarnessEvent> consumer, Consumer<Throwable> errorConsumer) {
            this.key = key;
            this.owner = owner;
            this.consumer = consumer;
            this.errorConsumer = errorConsumer;
            this.cursor = afterSequence;
        }

        private void offer(HarnessEvent event) {
            if (closed.get() || event.sequence() <= cursor) {
                return;
            }
            if (!queue.offer(event)) {
                fail(new IllegalStateException(
                        "Harness event subscriber fell behind at sequence " + cursor));
            }
        }

        private void replayThenDrain() {
            if (closed.get()) {
                return;
            }
            try {
                while (!closed.get()) {
                    List<HarnessEvent> page = store.readEvents(owner, key.sessionId(),
                            key.runId(), cursor, REPLAY_PAGE_SIZE);
                    if (page.isEmpty()) {
                        break;
                    }
                    for (HarnessEvent event : page) {
                        if (closed.get()) {
                            return;
                        }
                        if (event.sequence() <= cursor) {
                            continue;
                        }
                        deliver(event);
                        if (closed.get()) {
                            return;
                        }
                    }
                    if (page.size() < REPLAY_PAGE_SIZE) {
                        break;
                    }
                }
                while (!closed.get()) {
                    HarnessEvent event = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (event != null && event.sequence() > cursor) {
                        deliver(event);
                    }
                    if (!closed.get()
                            && System.currentTimeMillis() - lastTerminalRepair >= 5_000L) {
                        lastTerminalRepair = System.currentTimeMillis();
                        ensureTerminalEvent(owner, key.sessionId(), key.runId());
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                close();
            } catch (Throwable error) {
                fail(error);
            }
        }

        private void deliver(HarnessEvent event) {
            consumer.accept(event);
            cursor = event.sequence();
            if (event.isTerminalType()) {
                close();
            }
        }

        private void fail(Throwable error) {
            if (closed.compareAndSet(false, true)) {
                remove(this);
                queue.clear();
                try {
                    errorConsumer.accept(error);
                } catch (RuntimeException ignored) {
                    // A broken error callback is isolated from durable event publishing.
                }
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                remove(this);
                queue.clear();
            }
        }
    }
}
