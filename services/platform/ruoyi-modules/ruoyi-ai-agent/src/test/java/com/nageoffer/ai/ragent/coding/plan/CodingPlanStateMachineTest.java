package com.nageoffer.ai.ragent.coding.plan;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F20-A op3 计划状态机判据：revision/hash/taskId 三项陈旧校验、幂等重放、
 * 反馈不得改写任务身份、批准后不可再改计划。
 */
@Tag("dev")
class CodingPlanStateMachineTest {

    private static final long NOW = 1_000_000L;

    private CodingPlanState awaitingPlan() {
        CodingPlanState draft = CodingPlanState.create(UUID.randomUUID(), "build it", NOW);
        return draft.replacePlan("## plan\n1. a\n2. b",
                List.of(new PlanStepState("a", "first", List.of()),
                        new PlanStepState("b", "second", List.of("a"))), NOW + 1);
    }

    @Test
    void draftBecomesAwaitingApprovalWithCanonicalHash() {
        CodingPlanState plan = awaitingPlan();
        assertThat(plan.reviewState()).isEqualTo(PlanReviewState.AWAITING_APPROVAL);
        assertThat(plan.mode()).isEqualTo(PlanMode.PLAN);
        assertThat(plan.revision()).isEqualTo(1);
        assertThat(plan.canonicalHash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void approvalValidatesTaskRevisionAndHash() {
        CodingPlanState plan = awaitingPlan();
        String hash = plan.canonicalHash();

        assertThatThrownBy(() -> plan.approve(UUID.randomUUID(), plan.revision(), hash, "k1", NOW + 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");
        assertThatThrownBy(() -> plan.approve(plan.taskId(), plan.revision() + 1, hash, "k1", NOW + 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");
        assertThatThrownBy(() -> plan.approve(plan.taskId(), plan.revision(), "c".repeat(64), "k1", NOW + 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");

        CodingPlanState approved = plan.approve(plan.taskId(), plan.revision(), hash, "k1", NOW + 2);
        assertThat(approved.mode()).isEqualTo(PlanMode.BUILD);
        assertThat(approved.reviewState()).isEqualTo(PlanReviewState.APPROVED);
        assertThat(approved.revision()).isEqualTo(2);
        assertThat(approved.approvalReceipts()).containsKey("k1");
    }

    @Test
    void sameIdempotencyKeyReplaysButCannotRebind() {
        CodingPlanState plan = awaitingPlan();
        String hash = plan.canonicalHash();
        CodingPlanState approved = plan.approve(plan.taskId(), plan.revision(), hash, "k1", NOW + 2);

        assertThat(approved.approve(plan.taskId(), plan.revision(), hash, "k1", NOW + 3))
                .isSameAs(approved);
        assertThatThrownBy(() -> approved.approve(plan.taskId(), plan.revision(), "d".repeat(64),
                "k1", NOW + 3))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("idempotency");
    }

    @Test
    void revisionFeedbackIsDurableAndIdempotent() {
        CodingPlanState plan = awaitingPlan();
        CodingPlanState revised = plan.requestRevision("f1", "tighten step a", NOW + 2);
        assertThat(revised.reviewState()).isEqualTo(PlanReviewState.REVISION_REQUESTED);
        assertThat(revised.revision()).isEqualTo(2);
        assertThat(revised.feedbackHistory()).hasSize(1);

        assertThat(revised.requestRevision("f1", "tighten step a", NOW + 3)).isSameAs(revised);
        assertThatThrownBy(() -> revised.requestRevision("f1", "different text", NOW + 3))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("idempotency");
        assertThatThrownBy(() -> plan.requestRevision(null, "x", NOW + 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvedPlanIsImmutableForRevision() {
        CodingPlanState plan = awaitingPlan();
        CodingPlanState approved = plan.approve(plan.taskId(), plan.revision(),
                plan.canonicalHash(), "k1", NOW + 2);
        assertThatThrownBy(() -> approved.requestRevision("f2", "change later", NOW + 3))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.replacePlan("new", List.of(
                new PlanStepState("c", "third", List.of())), NOW + 4))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hashCoversStepsAndText() {
        CodingPlanState plan = awaitingPlan();
        CodingPlanState changed = plan.replacePlan("## plan\n1. a\n2. c",
                List.of(new PlanStepState("a", "first", List.of()),
                        new PlanStepState("c", "third", List.of("a"))), NOW + 2);
        assertThat(changed.canonicalHash()).isNotEqualTo(plan.canonicalHash());
    }
}
