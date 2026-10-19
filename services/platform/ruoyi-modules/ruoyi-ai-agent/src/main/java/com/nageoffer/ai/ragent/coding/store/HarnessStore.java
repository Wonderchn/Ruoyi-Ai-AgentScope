package com.nageoffer.ai.ragent.coding.store;

import com.nageoffer.ai.ragent.coding.model.HarnessEvent;
import com.nageoffer.ai.ragent.coding.model.HarnessMessage;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;

import java.util.List;
import java.util.Optional;

/**
 * Durable harness store. Snapshots are atomically replaced; message/event ledgers are
 * append-only and sequence-dense per owner/session/run.
 */
public interface HarnessStore {

    HarnessSessionState createSession(HarnessSessionState session);

    Optional<HarnessSessionState> findSession(HarnessOwner owner, String sessionId);

    List<HarnessSessionState> listSessions(HarnessOwner owner);

    HarnessSessionState setSessionPinned(HarnessOwner owner, String sessionId, boolean pinned);

    HarnessSessionState setSessionDeleted(HarnessOwner owner, String sessionId, boolean deleted);

    HarnessSessionState saveSession(HarnessOwner owner, HarnessSessionState session,
                                    long expectedRevision);

    HarnessRunState createRun(HarnessOwner owner, HarnessRunState run);

    Optional<HarnessRunState> findRun(HarnessOwner owner, String sessionId, String runId);

    List<HarnessRunState> listRuns(HarnessOwner owner, String sessionId);

    HarnessRunState saveRun(HarnessOwner owner, HarnessRunState run, long expectedRevision);

    HarnessMessage appendMessage(HarnessOwner owner, HarnessMessage message);

    List<HarnessMessage> readMessages(HarnessOwner owner, String sessionId, long afterSequence,
                                      int limit);

    HarnessEvent appendEvent(HarnessOwner owner, HarnessEvent event);

    List<HarnessEvent> readEvents(HarnessOwner owner, String sessionId, String runId,
                                  long afterSequence, int limit);
}
