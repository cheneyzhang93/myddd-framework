package org.deep361.myddd.domain.event;

import org.deep361.myddd.kernel.ddd.event.DomainEventStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionStageExecutorTest {

    private final TransactionStageExecutor executor = new TransactionStageExecutor();

    @AfterEach
    void cleanup() {
        EventTestSupport.resetTransactionState();
    }

    @Test
    void in_transaction_should_execute_inline() {
        AtomicBoolean ran = new AtomicBoolean();

        executor.execute(DomainEventStage.IN_TRANSACTION, () -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    void in_transaction_should_propagate_failure() {
        assertThatThrownBy(() -> executor.execute(DomainEventStage.IN_TRANSACTION, () -> {
            throw new IllegalStateException("boom-in-tx");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom-in-tx");
    }

    @Test
    void after_commit_should_defer_until_commit() {
        List<String> calls = new ArrayList<>();
        EventTestSupport.beginTransaction();

        executor.execute(DomainEventStage.AFTER_COMMIT, () -> calls.add("after-commit"));

        assertThat(calls).isEmpty();
        EventTestSupport.commitTransaction();
        assertThat(calls).containsExactly("after-commit");
    }

    @Test
    void after_commit_should_not_run_on_rollback() {
        List<String> calls = new ArrayList<>();
        EventTestSupport.beginTransaction();

        executor.execute(DomainEventStage.AFTER_COMMIT, () -> calls.add("after-commit"));
        EventTestSupport.rollbackTransaction();

        assertThat(calls).isEmpty();
    }

    @Test
    void after_commit_should_skip_without_synchronization() {
        List<String> calls = new ArrayList<>();

        executor.execute(DomainEventStage.AFTER_COMMIT, () -> calls.add("after-commit"));

        assertThat(calls).isEmpty();
    }

    @Test
    void after_commit_should_skip_when_no_actual_transaction() {
        List<String> calls = new ArrayList<>();
        EventTestSupport.beginSynchronizationWithoutTransaction();

        executor.execute(DomainEventStage.AFTER_COMMIT, () -> calls.add("after-commit"));

        assertThat(calls).isEmpty();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void after_commit_should_skip_when_synchronization_inactive_but_transaction_active() {
        List<String> calls = new ArrayList<>();
        EventTestSupport.beginActualTransactionWithoutSynchronization();

        // 同步未激活时注册钩子会直接抛异常；正确行为是跳过，绝不能把事件丢掉后抛出去
        executor.execute(DomainEventStage.AFTER_COMMIT, () -> calls.add("after-commit"));

        assertThat(calls).isEmpty();
    }

    @Test
    void after_commit_should_run_inline_when_registered_from_within_after_commit() {
        List<String> calls = new ArrayList<>();
        EventTestSupport.beginTransaction();

        // 事务同步列表在回调触发前已被快照：回调内新注册的钩子永远不会收到 afterCommit
        executor.execute(DomainEventStage.AFTER_COMMIT, () -> {
            calls.add("outer");
            executor.execute(DomainEventStage.AFTER_COMMIT, () -> calls.add("nested"));
        });
        EventTestSupport.commitTransaction();

        assertThat(calls).containsExactly("outer", "nested");
    }

    @Test
    void after_commit_should_defer_again_in_a_later_transaction() {
        List<String> calls = new ArrayList<>();
        EventTestSupport.beginTransaction();
        executor.execute(DomainEventStage.AFTER_COMMIT, () -> calls.add("first"));
        EventTestSupport.commitTransaction();

        // 上一次提交的回调状态不得泄漏到下一个事务：第二个动作必须重新被延迟
        EventTestSupport.beginTransaction();
        executor.execute(DomainEventStage.AFTER_COMMIT, () -> calls.add("second"));
        assertThat(calls).containsExactly("first");
        EventTestSupport.commitTransaction();

        assertThat(calls).containsExactly("first", "second");
    }

    @Test
    void after_commit_should_register_one_hook_per_action_in_order() {
        List<String> calls = new ArrayList<>();
        EventTestSupport.beginTransaction();

        executor.execute(DomainEventStage.AFTER_COMMIT, () -> calls.add("a"));
        executor.execute(DomainEventStage.AFTER_COMMIT, () -> calls.add("b"));

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(2);
        EventTestSupport.commitTransaction();
        assertThat(calls).containsExactly("a", "b");
    }

    @Test
    void execute_should_reject_null_arguments() {
        assertThatThrownBy(() -> executor.execute(null, () -> {
        })).isInstanceOf(NullPointerException.class).hasMessage("投递阶段不能为 null");
        assertThatThrownBy(() -> executor.execute(DomainEventStage.IN_TRANSACTION, null))
                .isInstanceOf(NullPointerException.class).hasMessage("监听器动作不能为 null");
    }
}
