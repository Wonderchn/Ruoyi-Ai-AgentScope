package com.nageoffer.ai.ragent.coding.approval;

import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F20-A op3 审批状态机判据：版本/参数哈希/属主绑定，重复 resolve 幂等但不得改判。
 */
@Tag("dev")
class ToolApprovalStateMachineTest {

    private static final String HASH = "a".repeat(64);
    private final HarnessOwner owner = new HarnessOwner("T1", 42L);

    private ToolApproval pending() {
        return ToolApproval.pending("ap1", owner, "s1", "r1", "call-1", "write_file",
                "WRITE", "write a file", Map.of("path", "a.txt", "content", "x"), HASH, 1000);
    }

    @Test
    void approvesExactlyOnceWithExpectedRevisionAndHash() {
        ToolApproval pending = pending();
        ToolApproval resolved = pending.resolve("d1", ApprovalDecision.APPROVE, 0, HASH,
                owner, "s1", "ok", 2000);

        assertThat(resolved.state()).isEqualTo(ApprovalState.APPROVED);
        assertThat(resolved.revision()).isEqualTo(1);
        assertThat(resolved.resolvedBy()).isEqualTo(42L);
        assertThat(resolved.resolvedAt()).isEqualTo(2000);
        assertThat(resolved.consumed()).isFalse();
    }

    @Test
    void duplicateResolveWithSameDecisionIsIdempotent() {
        ToolApproval resolved = pending().resolve("d1", ApprovalDecision.APPROVE, 0, HASH,
                owner, "s1", null, 2000);
        ToolApproval replay = resolved.resolve("d1", ApprovalDecision.APPROVE, 0, HASH,
                owner, "s1", null, 3000);
        assertThat(replay).isSameAs(resolved);
    }

    @Test
    void duplicateResolveCannotFlipTheDecision() {
        ToolApproval resolved = pending().resolve("d1", ApprovalDecision.APPROVE, 0, HASH,
                owner, "s1", null, 2000);
        assertThatThrownBy(() -> resolved.resolve("d2", ApprovalDecision.DENY, 0, HASH,
                owner, "s1", null, 3000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already resolved");
    }

    @Test
    void staleRevisionAndMismatchedArgumentsAreRejected() {
        assertThatThrownBy(() -> pending().resolve("d1", ApprovalDecision.APPROVE, 7, HASH,
                owner, "s1", null, 2000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revision");
        assertThatThrownBy(() -> pending().resolve("d1", ApprovalDecision.APPROVE, 0, "b".repeat(64),
                owner, "s1", null, 2000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arguments");
    }

    @Test
    void foreignOwnerOrSessionCannotResolve() {
        assertThatThrownBy(() -> pending().resolve("d1", ApprovalDecision.APPROVE, 0, HASH,
                new HarnessOwner("T2", 42L), "s1", null, 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending().resolve("d1", ApprovalDecision.APPROVE, 0, HASH,
                owner, "s2", null, 2000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consumedApprovalCannotBeConsumedTwice() {
        ToolApproval resolved = pending().resolve("d1", ApprovalDecision.APPROVE, 0, HASH,
                owner, "s1", null, 2000);
        ToolApproval consumed = resolved.markConsumed(2500);
        assertThat(consumed.consumed()).isTrue();
        assertThat(consumed.revision()).isEqualTo(2);
        assertThatThrownBy(() -> consumed.markConsumed(2600))
                .isInstanceOf(IllegalStateException.class);
    }
}
