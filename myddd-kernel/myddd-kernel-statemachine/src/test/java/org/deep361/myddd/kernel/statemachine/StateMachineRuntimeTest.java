package org.deep361.myddd.kernel.statemachine;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateMachineRuntimeTest {

    private static final StateMachineFixtures.OrderState CREATED = StateMachineFixtures.OrderState.CREATED;
    private static final StateMachineFixtures.OrderState PAID = StateMachineFixtures.OrderState.PAID;
    private static final StateMachineFixtures.OrderState SHIPPED = StateMachineFixtures.OrderState.SHIPPED;
    private static final StateMachineFixtures.OrderEvent PAY = StateMachineFixtures.OrderEvent.PAY;
    private static final StateMachineFixtures.OrderEvent SHIP = StateMachineFixtures.OrderEvent.SHIP;

    @Test
    void should_fire_transition_and_write_state_back() {
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(CREATED);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(StateMachineFixtures.orderDefinition(), holder::get, holder::set);

        assertThat(machine.fire(PAY)).isEqualTo(PAID);
        assertThat(holder.get()).isEqualTo(PAID);
        assertThat(machine.currentState()).isEqualTo(PAID);
        assertThat(machine.isTerminal()).isFalse();
    }

    @Test
    void should_report_can_fire_only_for_declared_transitions() {
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(CREATED);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(StateMachineFixtures.orderDefinition(), holder::get, holder::set);

        assertThat(machine.canFire(PAY)).isTrue();
        assertThat(machine.canFire(SHIP)).isFalse();
        assertThat(machine.availableEvents())
                .containsExactly(StateMachineFixtures.OrderEvent.PAY, StateMachineFixtures.OrderEvent.CLOSE);
    }

    @Test
    void should_reject_event_without_transition_and_keep_state() {
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(CREATED);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(StateMachineFixtures.orderDefinition(), holder::get, holder::set);

        assertThatThrownBy(() -> machine.fire(SHIP))
                .isInstanceOf(StateMachineException.class)
                .hasMessage("当前状态 CREATED 不允许执行操作 SHIP");
        assertThat(holder.get()).isEqualTo(CREATED);
    }

    @Test
    void should_reject_guarded_transition_and_keep_state() {
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(CREATED);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(StateMachineFixtures.neverGuardDefinition(), holder::get, holder::set);

        assertThat(machine.canFire(PAY)).isFalse();
        assertThatThrownBy(() -> machine.fire(PAY))
                .isInstanceOf(StateMachineException.class)
                .hasMessage("当前状态 CREATED 不允许执行操作 PAY");
        assertThat(holder.get()).isEqualTo(CREATED);
    }

    @Test
    void should_allow_guarded_transition_when_guard_passes() {
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(CREATED);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(StateMachineFixtures.alwaysGuardDefinition(), holder::get, holder::set);

        assertThat(machine.canFire(PAY)).isTrue();
        assertThat(machine.fire(PAY)).isEqualTo(PAID);
    }

    @Test
    void should_report_terminal_state_without_available_events() {
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(SHIPPED);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(StateMachineFixtures.orderDefinition(), holder::get, holder::set);

        assertThat(machine.isTerminal()).isTrue();
        assertThat(machine.availableEvents()).isEmpty();
        assertThat(machine.canFire(PAY)).isFalse();
        assertThatThrownBy(() -> machine.fire(PAY)).isInstanceOf(StateMachineException.class);
    }

    @Test
    void should_restore_from_any_declared_state() {
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(PAID);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(StateMachineFixtures.orderDefinition(), holder::get, holder::set);

        assertThat(machine.currentState()).isEqualTo(PAID);
        assertThat(machine.availableEvents()).containsExactly(SHIP);
    }

    @Test
    void should_reject_undeclared_current_state_on_construction() {
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(StateMachineFixtures.OrderState.SHIPPED);

        assertThatThrownBy(() -> StateMachine.of(StateMachineFixtures.partialDefinition(), holder::get, holder::set))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHIPPED")
                .hasMessageContaining("未在状态机");
    }

    @Test
    void should_reject_uninitialized_state_and_null_event() {
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder = new StateMachineFixtures.Holder<>(null);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(StateMachineFixtures.orderDefinition(), () -> CREATED, holder::set);

        assertThatThrownBy(() -> machine.fire(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("事件不能为 null");
        assertThatThrownBy(() -> machine.canFire(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("事件不能为 null");
        assertThatThrownBy(() -> StateMachine.of(StateMachineFixtures.orderDefinition(), holder::get, holder::set))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("当前状态为 null：领域对象尚未初始化状态");
    }

    @Test
    void should_write_state_exactly_once_on_success() {
        List<StateMachineFixtures.OrderState> writes = new ArrayList<>();
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(CREATED);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(StateMachineFixtures.orderDefinition(), holder::get, state -> {
                    holder.set(state);
                    writes.add(state);
                });

        machine.fire(PAY);

        assertThat(writes).containsExactly(PAID);
    }

    @Test
    void should_not_write_state_on_either_rejection_path() {
        List<StateMachineFixtures.OrderState> writes = new ArrayList<>();
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(CREATED);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> orderMachine =
                StateMachine.of(StateMachineFixtures.orderDefinition(), holder::get, state -> {
                    holder.set(state);
                    writes.add(state);
                });
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> guardedMachine =
                StateMachine.of(StateMachineFixtures.neverGuardDefinition(), holder::get, state -> {
                    holder.set(state);
                    writes.add(state);
                });

        assertThatThrownBy(() -> orderMachine.fire(SHIP)).isInstanceOf(StateMachineException.class);
        assertThatThrownBy(() -> guardedMachine.fire(PAY)).isInstanceOf(StateMachineException.class);

        assertThat(writes).isEmpty();
        assertThat(holder.get()).isEqualTo(CREATED);
    }

    @Test
    void should_expose_from_event_and_target_to_guard_on_probe_and_fire() {
        List<TransitionContext<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent>> observed =
                new ArrayList<>();
        StateMachineDefinition<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> definition =
                StateMachineDefinition.<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent>builder("observed")
                        .initial(CREATED)
                        .transition(CREATED, PAY, PAID, context -> {
                            observed.add(context);
                            return true;
                        })
                        .transition(PAID, SHIP, SHIPPED, context -> {
                            observed.add(context);
                            return true;
                        })
                        .terminal(SHIPPED)
                        .build();
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(CREATED);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(definition, holder::get, holder::set);

        assertThat(machine.canFire(PAY)).isTrue();
        assertThat(observed).containsExactly(new TransitionContext<>(CREATED, PAY, PAID));
        observed.clear();

        machine.fire(PAY);
        assertThat(observed).containsExactly(new TransitionContext<>(CREATED, PAY, PAID));
        observed.clear();

        assertThat(machine.canFire(SHIP)).isTrue();
        observed.clear();

        machine.fire(SHIP);
        assertThat(observed).containsExactly(new TransitionContext<>(PAID, SHIP, SHIPPED));
        assertThat(holder.get()).isEqualTo(SHIPPED);
    }

    @Test
    void should_reject_state_that_becomes_null_after_binding() {
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(CREATED);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(StateMachineFixtures.orderDefinition(), holder::get, holder::set);
        holder.set(null);

        assertThatThrownBy(machine::currentState)
                .isInstanceOf(NullPointerException.class)
                .hasMessage("当前状态为 null：领域对象尚未初始化状态");
        assertThatThrownBy(() -> machine.canFire(PAY))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("当前状态为 null：领域对象尚未初始化状态");
        assertThatThrownBy(() -> machine.fire(PAY))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("当前状态为 null：领域对象尚未初始化状态");
    }

    @Test
    void should_read_state_through_supplier_on_every_call() {
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(CREATED);
        StateMachine<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> machine =
                StateMachine.of(StateMachineFixtures.orderDefinition(), holder::get, holder::set);
        holder.set(PAID);

        assertThat(machine.currentState()).isEqualTo(PAID);
        assertThat(machine.canFire(SHIP)).isTrue();
    }

    @Test
    void should_expose_definition() {
        StateMachineDefinition<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> definition =
                StateMachineFixtures.orderDefinition();
        StateMachineFixtures.Holder<StateMachineFixtures.OrderState> holder =
                new StateMachineFixtures.Holder<>(CREATED);

        assertThat(StateMachine.of(definition, holder::get, holder::set).definition()).isSameAs(definition);
    }

    @Test
    void should_reject_null_collaborators() {
        assertThatThrownBy(() -> StateMachine.of(null, () -> CREATED, state -> {
        })).isInstanceOf(NullPointerException.class)
                .hasMessage("definition 不能为 null");
        assertThatThrownBy(() -> StateMachine.of(StateMachineFixtures.orderDefinition(), null, state -> {
        })).isInstanceOf(NullPointerException.class)
                .hasMessage("currentState 不能为 null");
        assertThatThrownBy(() -> StateMachine.of(StateMachineFixtures.orderDefinition(), () -> CREATED, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("stateWriter 不能为 null");
    }
}
