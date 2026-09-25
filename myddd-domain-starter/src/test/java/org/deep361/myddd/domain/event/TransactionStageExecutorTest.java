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
