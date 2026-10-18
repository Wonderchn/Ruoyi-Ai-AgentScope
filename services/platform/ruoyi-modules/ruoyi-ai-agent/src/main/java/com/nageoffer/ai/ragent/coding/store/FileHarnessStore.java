package com.nageoffer.ai.ragent.coding.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.coding.model.HarnessEvent;
import com.nageoffer.ai.ragent.coding.model.HarnessMessage;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * File-backed single-instance durable store (ported from the legacy FileHarnessStore).
 *
 * <p>State snapshots are written to a temp file, fsynced and atomically moved over the target.
 * Message and event ledgers are append-only JSON lines; a torn tail from a crash is truncated
 * before the next append. Cross-process event appends take an exclusive file lock; all mutations
 * under one session share a striped in-process lock.
 *
 * <p>Deliberate simplification: no multi-node/cluster implementation and no global crash-recovery
 * scan — the durable snapshots and ledgers are the source of truth, and the run state machine is
 * recovered per run on the next control-plane command.
 */
public class FileHarnessStore implements HarnessStore {

    private static final int MAX_READ_LIMIT = 10_000;
    private static final String SAFE_ID = "[A-Za-z0-9_-]{1,128}";
    private static final ReentrantLock[] LEDGER_LOCKS = createLockStripes(256);

    private final ObjectMapper objectMapper;
    private final Path dataRoot;
    private final ConcurrentMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> messageSequences = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, EventCursor> eventSequences = new ConcurrentHashMap<>();

    public FileHarnessStore(ObjectMapper objectMapper, String dataDirectory) {
        this(objectMapper, Path.of(dataDirectory).resolve("state"));
    }

    public FileHarnessStore(ObjectMapper objectMapper, Path dataRoot) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dataRoot);
        } catch (IOException e) {
            throw new HarnessStoreException("Cannot create Harness data directory " + this.dataRoot, e);
        }
    }

    // ------------------------------------------------------------------ sessions

    @Override
    public HarnessSessionState createSession(HarnessSessionState session) {
        Objects.requireNonNull(session, "session");
        verifyOwner(session.owner(), new HarnessOwner(session.tenantId(), session.userId()));
        requireSafeId("sessionId", session.sessionId());
        return withSessionLock(session.owner(), session.sessionId(), () -> {
            Path file = sessionFile(session.owner(), session.sessionId());
            if (Files.exists(file)) {
                throw new HarnessStoreException("Session already exists: " + session.sessionId());
            }
            atomicWrite(file, session);
            return session;
        });
    }

    @Override
    public Optional<HarnessSessionState> findSession(HarnessOwner owner, String sessionId) {
        requireSafeId("sessionId", sessionId);
        return withSessionLock(owner, sessionId, () -> readOptional(
                sessionFile(owner, sessionId), HarnessSessionState.class).map(state -> {
            verifyOwner(owner, state.owner());
            return state;
        }));
    }

    @Override
    public List<HarnessSessionState> listSessions(HarnessOwner owner) {
        Path ownerRoot = ownerRoot(owner);
        if (!Files.isDirectory(ownerRoot)) {
            return List.of();
        }
        try (var directories = Files.list(ownerRoot)) {
            return directories.filter(Files::isDirectory)
                    .map(path -> path.resolve("session.json"))
                    .filter(Files::isRegularFile)
                    .map(path -> read(path, HarnessSessionState.class))
                    .peek(state -> verifyOwner(owner, state.owner()))
                    .filter(state -> state.deletedAt() == 0)
                    .sorted(Comparator.comparingLong(HarnessSessionState::pinnedAt).reversed()
                            .thenComparing(Comparator.comparingLong(
                                    HarnessSessionState::updatedAt).reversed()))
                    .toList();
        } catch (IOException e) {
            throw new HarnessStoreException("Cannot list Harness sessions", e);
        }
    }

    @Override
    public HarnessSessionState setSessionPinned(HarnessOwner owner, String sessionId,
                                                boolean pinned) {
        requireSafeId("sessionId", sessionId);
        return withSessionLock(owner, sessionId, () -> {
            Path file = sessionFile(owner, sessionId);
            HarnessSessionState current = readRequired(file, HarnessSessionState.class, "session");
            verifyOwner(owner, current.owner());
            if (current.deletedAt() != 0) {
                throw new HarnessStoreException("会话已删除，请先恢复会话");
            }
            long pinnedAt = pinned ? (current.pinnedAt() == 0
                    ? System.currentTimeMillis() : current.pinnedAt()) : 0;
            HarnessSessionState updated = current.withSidebarState(pinnedAt, 0);
            atomicWrite(file, updated);
            return updated;
        });
    }

    @Override
    public HarnessSessionState setSessionDeleted(HarnessOwner owner, String sessionId,
                                                 boolean deleted) {
        requireSafeId("sessionId", sessionId);
        return withSessionLock(owner, sessionId, () -> {
            Path file = sessionFile(owner, sessionId);
            HarnessSessionState current = readRequired(file, HarnessSessionState.class, "session");
            verifyOwner(owner, current.owner());
            if (deleted && current.deletedAt() == 0
                    && listRuns(owner, sessionId).stream().anyMatch(run -> !run.status().isTerminal())) {
                throw new HarnessStoreException("该会话还有未结束的任务，请先停止任务再删除会话");
            }
            long deletedAt = deleted ? (current.deletedAt() == 0
                    ? System.currentTimeMillis() : current.deletedAt()) : 0;
            HarnessSessionState updated = current.withSidebarState(current.pinnedAt(), deletedAt);
            atomicWrite(file, updated);
            return updated;
        });
    }

    @Override
    public HarnessSessionState saveSession(HarnessOwner owner, HarnessSessionState session,
                                           long expectedRevision) {
        requireSafeId("sessionId", session.sessionId());
        verifyOwner(owner, session.owner());
        return withSessionLock(owner, session.sessionId(), () -> {
            Path file = sessionFile(owner, session.sessionId());
            HarnessSessionState current = readRequired(file, HarnessSessionState.class, "session");
            verifyOwner(owner, current.owner());
            if (current.revision() != expectedRevision || session.revision() != expectedRevision) {
                throw new HarnessOptimisticLockException("session " + session.sessionId(),
                        expectedRevision, current.revision());
            }
            if (!current.workspace().equals(session.workspace())) {
                throw new HarnessStoreException("A session workspace lease is immutable");
            }
            if (!current.model().equals(session.model())
                    || current.permissionMode() != session.permissionMode()
                    || current.approvalPolicy() != session.approvalPolicy()) {
                throw new HarnessStoreException(
                        "A session model, permission mode and approval policy are immutable");
            }
            HarnessSessionState stored = session
                    .withSidebarState(current.pinnedAt(), current.deletedAt())
                    .withRevision(expectedRevision + 1);
            atomicWrite(file, stored);
            return stored;
        });
    }

    // ------------------------------------------------------------------ runs

    @Override
    public HarnessRunState createRun(HarnessOwner owner, HarnessRunState run) {
        requireSafeId("sessionId", run.sessionId());
        requireSafeId("runId", run.runId());
        verifyOwner(owner, run.owner());
        return withSessionLock(owner, run.sessionId(), () -> {
            HarnessSessionState session = readRequired(sessionFile(owner, run.sessionId()),
                    HarnessSessionState.class, "session");
            verifyOwner(owner, session.owner());
            if (session.deletedAt() != 0) {
                throw new HarnessStoreException("会话已删除，请先恢复会话");
            }
            if (run.modelRoute() == null
                    || !session.model().equals(run.modelRoute().requestedModel())) {
                throw new HarnessStoreException("Run model route does not belong to its session");
            }
            Path file = runFile(owner, run.sessionId(), run.runId());
            if (Files.exists(file)) {
                throw new HarnessStoreException("Run already exists: " + run.runId());
            }
            atomicWrite(file, run);
            return run;
        });
    }

    @Override
    public Optional<HarnessRunState> findRun(HarnessOwner owner, String sessionId, String runId) {
        requireSafeId("sessionId", sessionId);
        requireSafeId("runId", runId);
        return withSessionLock(owner, sessionId, () -> {
            HarnessSessionState session = readOptional(sessionFile(owner, sessionId),
                    HarnessSessionState.class).orElse(null);
            if (session == null) {
                return Optional.empty();
            }
            verifyOwner(owner, session.owner());
            return readOptional(runFile(owner, sessionId, runId), HarnessRunState.class)
                    .map(state -> {
                        verifyOwner(owner, state.owner());
                        return state;
                    });
        });
    }

    @Override
    public List<HarnessRunState> listRuns(HarnessOwner owner, String sessionId) {
        requireSafeId("sessionId", sessionId);
        return withSessionLock(owner, sessionId, () -> {
            HarnessSessionState session = readRequired(sessionFile(owner, sessionId),
                    HarnessSessionState.class, "session");
            verifyOwner(owner, session.owner());
            Path runs = sessionDirectory(owner, sessionId).resolve("runs");
            if (!Files.isDirectory(runs)) {
                return List.of();
            }
            try (var directories = Files.list(runs)) {
                return directories.filter(Files::isDirectory)
                        .map(path -> path.resolve("state.json"))
                        .filter(Files::isRegularFile)
                        .map(path -> read(path, HarnessRunState.class))
                        .peek(state -> verifyOwner(owner, state.owner()))
                        .sorted(Comparator.comparingLong(HarnessRunState::createdAt))
                        .toList();
            } catch (IOException e) {
                throw new HarnessStoreException("Cannot list runs for session " + sessionId, e);
            }
        });
    }

    @Override
    public HarnessRunState saveRun(HarnessOwner owner, HarnessRunState run,
                                   long expectedRevision) {
        requireSafeId("sessionId", run.sessionId());
        requireSafeId("runId", run.runId());
        verifyOwner(owner, run.owner());
        return withSessionLock(owner, run.sessionId(), () -> {
            Path file = runFile(owner, run.sessionId(), run.runId());
            HarnessSessionState session = readRequired(sessionFile(owner, run.sessionId()),
                    HarnessSessionState.class, "session");
            verifyOwner(owner, session.owner());
            HarnessRunState current = readRequired(file, HarnessRunState.class, "run");
            verifyOwner(owner, current.owner());
            if (current.revision() != expectedRevision || run.revision() != expectedRevision) {
                throw new HarnessOptimisticLockException("run " + run.runId(), expectedRevision,
                        current.revision());
            }
            if (!current.originalRequirement().equals(run.originalRequirement())
                    || current.permissionMode() != run.permissionMode()
                    || current.permissionRevision() != run.permissionRevision()
                    || !Objects.equals(current.modelRoute(), run.modelRoute())
                    || !session.model().equals(run.modelRoute().requestedModel())) {
                throw new HarnessStoreException("Run anchors are immutable");
            }
            HarnessRunState stored = run.withRevision(expectedRevision + 1);
            atomicWrite(file, stored);
            return stored;
        });
    }

    // ------------------------------------------------------------------ ledgers

    @Override
    public HarnessMessage appendMessage(HarnessOwner owner, HarnessMessage message) {
        requireSafeId("sessionId", message.sessionId());
        requireSafeId("runId", message.runId());
        return withSessionLock(owner, message.sessionId(), () -> {
            requireRun(owner, message.sessionId(), message.runId());
            Path ledger = messagesFile(owner, message.sessionId());
            String key = sessionKey(owner, message.sessionId());
            long sequence = messageSequences.computeIfAbsent(key,
                    ignored -> new AtomicLong(maxMessageSequence(ledger))).incrementAndGet();
            HarnessMessage stored = message.withSequence(sequence);
            appendJsonLine(ledger, stored);
            return stored;
        });
    }

    @Override
    public List<HarnessMessage> readMessages(HarnessOwner owner, String sessionId,
                                             long afterSequence, int limit) {
        validateRead(afterSequence, limit);
        requireSafeId("sessionId", sessionId);
        return withSessionLock(owner, sessionId, () -> {
            HarnessSessionState session = readRequired(sessionFile(owner, sessionId),
                    HarnessSessionState.class, "session");
            verifyOwner(owner, session.owner());
            return readLedgerPage(messagesFile(owner, sessionId), HarnessMessage.class,
                    HarnessMessage::sequence, afterSequence, limit);
        });
    }

    @Override
    public HarnessEvent appendEvent(HarnessOwner owner, HarnessEvent event) {
        requireSafeId("sessionId", event.sessionId());
        requireSafeId("runId", event.runId());
        return withSessionLock(owner, event.sessionId(), () -> {
            requireRun(owner, event.sessionId(), event.runId());
            Path ledger = eventsFile(owner, event.sessionId(), event.runId());
            String key = sessionKey(owner, event.sessionId()) + "/" + event.runId();
            return withLedgerFileLock(ledger, () -> appendEventUnderLedgerLock(key, ledger, event));
        });
    }

    @Override
    public List<HarnessEvent> readEvents(HarnessOwner owner, String sessionId, String runId,
                                         long afterSequence, int limit) {
        validateRead(afterSequence, limit);
        requireSafeId("sessionId", sessionId);
        requireSafeId("runId", runId);
        return withSessionLock(owner, sessionId, () -> {
            requireRun(owner, sessionId, runId);
            return readLedgerPage(eventsFile(owner, sessionId, runId), HarnessEvent.class,
                    HarnessEvent::sequence, afterSequence, limit);
        });
    }

    // ------------------------------------------------------------------ internals

    private void requireRun(HarnessOwner owner, String sessionId, String runId) {
        HarnessRunState run = readRequired(runFile(owner, sessionId, runId),
                HarnessRunState.class, "run");
        verifyOwner(owner, run.owner());
    }

    private record EventCursor(long sequence, long byteLength) {
    }

    private HarnessEvent appendEventUnderLedgerLock(String key, Path ledger, HarnessEvent event) {
        try {
            Files.createDirectories(ledger.getParent());
            repairTornTail(ledger);
            long byteLength = Files.exists(ledger) ? Files.size(ledger) : 0L;
            EventCursor cached = eventSequences.get(key);
            long durableSequence = cached != null && cached.byteLength() == byteLength
                    ? cached.sequence() : maxEventSequence(ledger);
            if (durableSequence == Long.MAX_VALUE) {
                throw new HarnessStoreException("Harness event sequence is exhausted for " + ledger);
            }
            HarnessEvent stored = event.withSequence(durableSequence + 1);
            long committedLength = appendJsonLineTransactionally(ledger, stored, byteLength);
            eventSequences.put(key, new EventCursor(stored.sequence(), committedLength));
            return stored;
        } catch (IOException error) {
            throw new HarnessStoreException("Cannot append Harness event ledger " + ledger, error);
        }
    }

    private <T> T withLedgerFileLock(Path ledger, Supplier<T> operation) {
        Path lockFile = ledger.resolveSibling(ledger.getFileName() + ".lock")
                .toAbsolutePath().normalize();
        ReentrantLock processLock = LEDGER_LOCKS[Math.floorMod(lockFile.hashCode(),
                LEDGER_LOCKS.length)];
        processLock.lock();
        try {
            Files.createDirectories(lockFile.getParent());
            try (FileChannel lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                return operation.get();
            }
        } catch (IOException error) {
            throw new HarnessStoreException("Cannot lock Harness event ledger " + ledger, error);
        } finally {
            processLock.unlock();
        }
    }

    private long appendJsonLineTransactionally(Path file, Object value, long originalLength) {
        long bytesWritten = 0L;
        boolean appendStarted = false;
        try {
            byte[] json = objectMapper.writeValueAsBytes(value);
            ByteBuffer buffer = ByteBuffer.allocate(json.length + 1);
            buffer.put(json).put((byte) '\n').flip();
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                if (channel.size() != originalLength) {
                    throw new IOException("Harness event ledger changed while exclusively locked");
                }
                channel.position(originalLength);
                while (buffer.hasRemaining()) {
                    appendStarted = true;
                    int written = channel.write(buffer);
                    if (written < 0) {
                        throw new IOException("Unexpected end while appending Harness ledger");
                    }
                    if (written == 0) {
                        continue;
                    }
                    bytesWritten += written;
                }
                channel.force(true);
                long committedLength = originalLength + json.length + 1L;
                if (channel.size() != committedLength) {
                    throw new IOException("Harness event ledger append length mismatch");
                }
                return committedLength;
            }
        } catch (IOException | RuntimeException failure) {
            if (appendStarted) {
                try {
                    rollbackLedgerAppend(file, originalLength);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw new HarnessStoreException("Cannot atomically append Harness ledger " + file,
                    failure);
        }
    }

    private void rollbackLedgerAppend(Path file, long originalLength) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            if (channel.size() > originalLength) {
                channel.truncate(originalLength);
                channel.force(true);
            }
        }
    }

    private static ReentrantLock[] createLockStripes(int count) {
        ReentrantLock[] locks = new ReentrantLock[count];
        for (int index = 0; index < count; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private long maxMessageSequence(Path ledger) {
        return maxLedgerSequence(ledger, HarnessMessage.class, HarnessMessage::sequence);
    }

    private long maxEventSequence(Path ledger) {
        return maxLedgerSequence(ledger, HarnessEvent.class, HarnessEvent::sequence);
    }

    private <T> List<T> readLedgerPage(Path file, Class<T> type,
                                       ToLongFunction<T> sequenceExtractor,
                                       long afterSequence, int limit) {
        if (!Files.exists(file)) {
            return List.of();
        }
        boolean terminated = fileEndsWithNewline(file);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<T> records = new ArrayList<>(Math.min(limit, 1_000));
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    T record = objectMapper.readValue(line, type);
                    if (sequenceExtractor.applyAsLong(record) > afterSequence) {
                        records.add(record);
                        if (records.size() == limit) {
                            break;
                        }
                    }
                } catch (IOException parseError) {
                    if (!terminated && !hasNonBlankRemainder(reader)) {
                        break;
                    }
                    throw new HarnessStoreException("Corrupt Harness ledger at " + file
                            + " line " + lineNumber, parseError);
                }
            }
            return records;
        } catch (IOException e) {
            throw new HarnessStoreException("Cannot read Harness ledger " + file, e);
        }
    }

    private <T> long maxLedgerSequence(Path file, Class<T> type,
                                       ToLongFunction<T> sequenceExtractor) {
        if (!Files.exists(file)) {
            return 0;
        }
        boolean terminated = fileEndsWithNewline(file);
        long maximum = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    maximum = Math.max(maximum,
                            sequenceExtractor.applyAsLong(objectMapper.readValue(line, type)));
                } catch (IOException parseError) {
                    if (!terminated && !hasNonBlankRemainder(reader)) {
                        break;
                    }
                    throw new HarnessStoreException("Corrupt Harness ledger at " + file
                            + " line " + lineNumber, parseError);
                }
            }
            return maximum;
        } catch (IOException e) {
            throw new HarnessStoreException("Cannot scan Harness ledger " + file, e);
        }
    }

    private boolean hasNonBlankRemainder(BufferedReader reader) throws IOException {
        String remainder;
        while ((remainder = reader.readLine()) != null) {
            if (!remainder.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean fileEndsWithNewline(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() == 0) {
                return true;
            }
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.position(channel.size() - 1);
            channel.read(last);
            last.flip();
            return last.hasRemaining() && last.get() == (byte) '\n';
        } catch (IOException e) {
            throw new HarnessStoreException("Cannot inspect Harness ledger " + file, e);
        }
    }

    private void appendJsonLine(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            repairTornTail(file);
            byte[] json = objectMapper.writeValueAsBytes(value);
            ByteBuffer buffer = ByteBuffer.allocate(json.length + 1);
            buffer.put(json).put((byte) '\n').flip();
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(false);
            }
        } catch (IOException e) {
            throw new HarnessStoreException("Cannot append Harness ledger " + file, e);
        }
    }

    private void repairTornTail(Path file) throws IOException {
        if (!Files.exists(file) || Files.size(file) == 0 || fileEndsWithNewline(file)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            long position = channel.size() - 1;
            ByteBuffer byteBuffer = ByteBuffer.allocate(1);
            while (position >= 0) {
                byteBuffer.clear();
                channel.position(position);
                channel.read(byteBuffer);
                byteBuffer.flip();
                if (byteBuffer.hasRemaining() && byteBuffer.get() == (byte) '\n') {
                    channel.truncate(position + 1);
                    channel.force(true);
                    return;
                }
                position--;
            }
            channel.truncate(0);
            channel.force(true);
        }
    }

    private void atomicWrite(Path file, Object value) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new HarnessStoreException("Cannot persist Harness snapshot " + file, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The target snapshot has already been moved or the temp file is harmless.
                }
            }
        }
    }

    private <T> Optional<T> readOptional(Path file, Class<T> type) {
        return Files.exists(file) ? Optional.of(read(file, type)) : Optional.empty();
    }

    private <T> T readRequired(Path file, Class<T> type, String resource) {
        return readOptional(file, type)
                .orElseThrow(() -> new HarnessStoreException("Unknown " + resource + ": " + file));
    }

    private <T> T read(Path file, Class<T> type) {
        try {
            return objectMapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new HarnessStoreException("Cannot read Harness snapshot " + file, e);
        }
    }

    private Path ownerRoot(HarnessOwner owner) {
        requireSafeId("tenantId", owner.tenantId());
        return dataRoot.resolve(owner.tenantId()).resolve(Long.toString(owner.userId()));
    }

    private Path sessionDirectory(HarnessOwner owner, String sessionId) {
        requireSafeId("sessionId", sessionId);
        return ownerRoot(owner).resolve(sessionId);
    }

    private Path sessionFile(HarnessOwner owner, String sessionId) {
        return sessionDirectory(owner, sessionId).resolve("session.json");
    }

    private Path messagesFile(HarnessOwner owner, String sessionId) {
        return sessionDirectory(owner, sessionId).resolve("messages.jsonl");
    }

    private Path runDirectory(HarnessOwner owner, String sessionId, String runId) {
        requireSafeId("runId", runId);
        return sessionDirectory(owner, sessionId).resolve("runs").resolve(runId);
    }

    private Path runFile(HarnessOwner owner, String sessionId, String runId) {
        return runDirectory(owner, sessionId, runId).resolve("state.json");
    }

    private Path eventsFile(HarnessOwner owner, String sessionId, String runId) {
        return runDirectory(owner, sessionId, runId).resolve("events.jsonl");
    }

    private String sessionKey(HarnessOwner owner, String sessionId) {
        return owner.tenantId() + "/" + owner.userId() + "/" + sessionId;
    }

    private <T> T withSessionLock(HarnessOwner owner, String sessionId, Supplier<T> operation) {
        String key = sessionKey(owner, sessionId);
        ReentrantLock lock = sessionLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    private void verifyOwner(HarnessOwner expected, HarnessOwner actual) {
        if (!expected.equals(actual)) {
            throw new HarnessStoreException(
                    "Harness resource does not belong to the authenticated owner");
        }
    }

    private void requireSafeId(String label, String value) {
        if (value == null || !value.matches(SAFE_ID)) {
            throw new IllegalArgumentException(label + " contains unsafe characters");
        }
    }

    private void validateRead(long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > MAX_READ_LIMIT) {
            throw new IllegalArgumentException("Invalid ledger cursor or limit");
        }
    }
}
