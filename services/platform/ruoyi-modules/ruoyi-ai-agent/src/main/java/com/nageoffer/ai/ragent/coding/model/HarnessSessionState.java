package com.nageoffer.ai.ragent.coding.model;

import java.util.UUID;

/** Durable session metadata. The canonical workspace is immutable after creation. */
public record HarnessSessionState(
        int schemaVersion,
        String sessionId,
        String tenantId,
        Long userId,
        String workspace,
        String model,
        HarnessPermissionMode permissionMode,
        HarnessApprovalPolicy approvalPolicy,
        String title,
        String activeRunId,
        long createdAt,
        long updatedAt,
        long revision,
        long pinnedAt,
        long deletedAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public HarnessSessionState {
        approvalPolicy = approvalPolicy == null ? HarnessApprovalPolicy.ON_REQUEST : approvalPolicy;
        if (schemaVersion < 1 || sessionId == null || sessionId.isBlank()
                || tenantId == null || tenantId.isBlank() || userId == null || userId <= 0
                || workspace == null || workspace.isBlank() || model == null || model.isBlank()
                || permissionMode == null || createdAt <= 0 || updatedAt <= 0 || revision < 0
                || pinnedAt < 0 || deletedAt < 0) {
            throw new IllegalArgumentException("Invalid Harness session state");
        }
    }

    public static HarnessSessionState createWithId(String sessionId, HarnessOwner owner,
                                                   String workspace, String model,
                                                   HarnessPermissionMode permissionMode,
                                                   HarnessApprovalPolicy approvalPolicy,
                                                   String title, long now) {
        return new HarnessSessionState(CURRENT_SCHEMA_VERSION, sessionId,
                owner.tenantId(), owner.userId(), workspace, model,
                permissionMode == null ? HarnessPermissionMode.READ_ONLY : permissionMode,
                approvalPolicy, title, null, now, now, 0, 0, 0);
    }

    public static HarnessSessionState create(HarnessOwner owner, String workspace, String model,
                                             HarnessPermissionMode permissionMode, String title,
                                             long now) {
        return createWithId(UUID.randomUUID().toString(), owner, workspace, model, permissionMode,
                HarnessApprovalPolicy.ON_REQUEST, title, now);
    }

    public HarnessOwner owner() {
        return new HarnessOwner(tenantId, userId);
    }

    public HarnessSessionState withActiveRun(String runId, long now) {
        return new HarnessSessionState(schemaVersion, sessionId, tenantId, userId, workspace,
                model, permissionMode, approvalPolicy, title, runId, createdAt, now, revision,
                pinnedAt, deletedAt);
    }

    public HarnessSessionState withTitle(String newTitle, long now) {
        return new HarnessSessionState(schemaVersion, sessionId, tenantId, userId, workspace,
                model, permissionMode, approvalPolicy, newTitle, activeRunId, createdAt, now,
                revision, pinnedAt, deletedAt);
    }

    public HarnessSessionState withRevision(long newRevision) {
        return new HarnessSessionState(schemaVersion, sessionId, tenantId, userId, workspace,
                model, permissionMode, approvalPolicy, title, activeRunId, createdAt, updatedAt,
                newRevision, pinnedAt, deletedAt);
    }

    /** Sidebar changes do not alter the execution revision or conversation activity time. */
    public HarnessSessionState withSidebarState(long newPinnedAt, long newDeletedAt) {
        return new HarnessSessionState(schemaVersion, sessionId, tenantId, userId, workspace,
                model, permissionMode, approvalPolicy, title, activeRunId, createdAt, updatedAt,
                revision, newPinnedAt, newDeletedAt);
    }
}
