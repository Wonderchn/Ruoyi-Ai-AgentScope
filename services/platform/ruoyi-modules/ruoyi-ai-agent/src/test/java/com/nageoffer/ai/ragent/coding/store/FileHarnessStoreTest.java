package com.nageoffer.ai.ragent.coding.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.coding.model.HarnessEvent;
import com.nageoffer.ai.ragent.coding.model.HarnessMessage;
import com.nageoffer.ai.ragent.coding.model.HarnessMessageRole;
import com.nageoffer.ai.ragent.coding.model.HarnessModelRoute;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessPermissionMode;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F20-A op0/op2 持久化判据：快照原子替换、账本 append-only、会话恢复、属主隔离。
 */
@Tag("dev")
class FileHarnessStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private HarnessSessionState session(HarnessOwner owner, String id) {
        return HarnessSessionState.createWithId(id, owner, "/tmp/ws", "deepseek-flash",
                HarnessPermissionMode.READ_ONLY, null, "t", System.currentTimeMillis());
    }

    private HarnessRunState run(HarnessSessionState session, String id) {
        return HarnessRunState.createWithId(id, session, "do it", null,
                new HarnessModelRoute(1, session.model(), session.model()), System.currentTimeMillis());
    }

    @Test
    void snapshotReplacementIsAtomicAndLeavesNoTemporaryFiles(@TempDir Path root) throws Exception {
        FileHarnessStore store = new FileHarnessStore(mapper, root);
        HarnessOwner owner = new HarnessOwner("T1", 42L);
        HarnessSessionState state = session(owner, "s1");
        store.createSession(state);

        for (int revision = 0; revision < 5; revision++) {
            HarnessSessionState current = store.findSession(owner, "s1").orElseThrow();
            store.saveSession(owner, current.withTitle("title-" + revision, System.currentTimeMillis()),
                    current.revision());
        }

        try (var files = Files.walk(root)) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".tmp"))).isEmpty();
        }
        assertThat(store.findSession(owner, "s1").orElseThrow().title()).isEqualTo("title-4");
    }

    @Test
    void optimisticLockRejectsStaleWriters(@TempDir Path root) {
        FileHarnessStore store = new FileHarnessStore(mapper, root);
        HarnessOwner owner = new HarnessOwner("T1", 42L);
        HarnessSessionState state = store.createSession(session(owner, "s1"));

        HarnessSessionState first = store.saveSession(owner, state.withTitle("a", 1), state.revision());
        assertThatThrownBy(() -> store.saveSession(owner, state.withTitle("b", 2), state.revision()))
                .isInstanceOf(HarnessOptimisticLockException.class);
        assertThat(store.findSession(owner, "s1").orElseThrow().title()).isEqualTo("a");
        assertThat(first.revision()).isEqualTo(1);
    }

    @Test
    void messageLedgerIsAppendOnlyAndSequenceMonotonic(@TempDir Path root) throws Exception {
        FileHarnessStore store = new FileHarnessStore(mapper, root);
        HarnessOwner owner = new HarnessOwner("T1", 42L);
        HarnessSessionState session = store.createSession(session(owner, "s1"));
        store.createRun(owner, run(session, "r1"));

        for (int i = 0; i < 3; i++) {
            store.appendMessage(owner, HarnessMessage.draft("s1", "r1", HarnessMessageRole.USER,
                    "m" + i, null, List.of(), null, null, false, null, Map.of(), 10 + i));
        }

        List<HarnessMessage> all = store.readMessages(owner, "s1", 0, 100);
        assertThat(all).extracting(HarnessMessage::sequence).containsExactly(1L, 2L, 3L);
        assertThat(all).extracting(HarnessMessage::content).containsExactly("m0", "m1", "m2");
        assertThat(store.readMessages(owner, "s1", 2, 100))
                .extracting(HarnessMessage::sequence).containsExactly(3L);

        Path ledger = root.resolve("T1").resolve("42").resolve("s1").resolve("messages.jsonl");
        int linesBefore = Files.readAllLines(ledger).size();
        store.appendMessage(owner, HarnessMessage.draft("s1", "r1", HarnessMessageRole.ASSISTANT,
                "m3", null, List.of(), null, null, false, null, Map.of(), 20));
        assertThat(Files.readAllLines(ledger)).hasSize(linesBefore + 1);
    }

    @Test
    void tornLedgerTailIsRepairedBeforeNextAppend(@TempDir Path root) throws Exception {
        FileHarnessStore store = new FileHarnessStore(mapper, root);
        HarnessOwner owner = new HarnessOwner("T1", 42L);
        store.createSession(session(owner, "s1"));
        HarnessSessionState session = store.findSession(owner, "s1").orElseThrow();
        store.createRun(owner, run(session, "r1"));

        Path ledger = root.resolve("T1").resolve("42").resolve("s1").resolve("messages.jsonl");
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, "{\"partial\":", StandardOpenOption.CREATE);

        store.appendMessage(owner, HarnessMessage.draft("s1", "r1", HarnessMessageRole.USER,
                "recovered", null, List.of(), null, null, false, null, Map.of(), 30));

        List<HarnessMessage> messages = store.readMessages(owner, "s1", 0, 100);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).isEqualTo("recovered");
        assertThat(messages.get(0).sequence()).isEqualTo(1);
    }

    @Test
    void eventLedgerAssignsDenseSequences(@TempDir Path root) {
        FileHarnessStore store = new FileHarnessStore(mapper, root);
        HarnessOwner owner = new HarnessOwner("T1", 42L);
        HarnessSessionState session = store.createSession(session(owner, "s1"));
        store.createRun(owner, run(session, "r1"));

        for (int i = 0; i < 4; i++) {
            store.appendEvent(owner, HarnessEvent.draft("s1", "r1", "e" + i, null, null, null,
                    Map.of("i", i), 100 + i));
        }
        assertThat(store.readEvents(owner, "s1", "r1", 0, 100))
                .extracting(HarnessEvent::sequence).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void sessionDeleteRestoreKeepsSidebarState(@TempDir Path root) {
        FileHarnessStore store = new FileHarnessStore(mapper, root);
        HarnessOwner owner = new HarnessOwner("T1", 42L);
        HarnessSessionState state = store.createSession(session(owner, "s1"));
        store.setSessionPinned(owner, "s1", true);
        HarnessSessionState pinned = store.findSession(owner, "s1").orElseThrow();
        assertThat(pinned.pinnedAt()).isPositive();

        store.setSessionDeleted(owner, "s1", true);
        assertThat(store.listSessions(owner)).isEmpty();
        assertThatThrownBy(() -> store.setSessionPinned(owner, "s1", true))
                .isInstanceOf(HarnessStoreException.class)
                .hasMessageContaining("已删除");

        HarnessSessionState restored = store.setSessionDeleted(owner, "s1", false);
        assertThat(restored.deletedAt()).isZero();
        assertThat(restored.pinnedAt()).isEqualTo(pinned.pinnedAt());
        assertThat(state.revision()).isZero();
        assertThat(store.listSessions(owner)).hasSize(1);
    }

    @Test
    void foreignOwnerCannotReadOrOverwrite(@TempDir Path root) {
        FileHarnessStore store = new FileHarnessStore(mapper, root);
        HarnessOwner owner = new HarnessOwner("T1", 42L);
        HarnessOwner other = new HarnessOwner("T2", 42L);
        HarnessSessionState state = store.createSession(session(owner, "s1"));

        assertThat(store.findSession(other, "s1")).isEmpty();
        assertThatThrownBy(() -> store.saveSession(other, state, 0))
                .isInstanceOf(HarnessStoreException.class);
        assertThat(store.listSessions(other)).isEmpty();
    }
}
