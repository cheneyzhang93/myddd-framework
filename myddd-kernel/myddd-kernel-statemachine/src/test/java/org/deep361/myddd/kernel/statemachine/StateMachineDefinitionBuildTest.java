package org.deep361.myddd.kernel.statemachine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateMachineDefinitionBuildTest {

    private static final StateMachineFixtures.OrderState CREATED = StateMachineFixtures.OrderState.CREATED;
    private static final StateMachineFixtures.OrderState PAID = StateMachineFixtures.OrderState.PAID;
    private static final StateMachineFixtures.OrderState SHIPPED = StateMachineFixtures.OrderState.SHIPPED;
    private static final StateMachineFixtures.OrderState CLOSED = StateMachineFixtures.OrderState.CLOSED;
    private static final StateMachineFixtures.OrderEvent PAY = StateMachineFixtures.OrderEvent.PAY;
    private static final StateMachineFixtures.OrderEvent SHIP = StateMachineFixtures.OrderEvent.SHIP;
    private static final StateMachineFixtures.OrderEvent CLOSE = StateMachineFixtures.OrderEvent.CLOSE;

    @Test
    void should_build_definition_with_states_inferred_from_transitions() {
        StateMachineDefinition<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> definition =
                StateMachineFixtures.orderDefinition();

        assertThat(definition.name()).isEqualTo("order");
        assertThat(definition.initialState()).isEqualTo(CREATED);
        assertThat(definition.states()).containsExactly(CREATED, PAID, SHIPPED, CLOSED);
        assertThat(definition.terminalStates()).containsExactly(SHIPPED, CLOSED);
        assertThat(definition.events()).containsExactly(PAY, SHIP, CLOSE);
    }

    @Test
    void should_expose_transitions_in_declaration_order() {
        StateMachineDefinition<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> definition =
                StateMachineFixtures.orderDefinition();

        assertThat(definition.transitions()).extracting(Transition::describe).containsExactly(
                "CREATED --PAY--> PAID",
                "PAID --SHIP--> SHIPPED",
                "CREATED --CLOSE--> CLOSED");
    }

    @Test
    void should_lookup_transition_by_state_and_event() {
        StateMachineDefinition<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> definition =
                StateMachineFixtures.orderDefinition();

        assertThat(definition.transition(CREATED, PAY)).isPresent();
        assertThat(definition.targetState(CREATED, PAY)).isEqualTo(PAID);
        assertThat(definition.transition(SHIPPED, PAY)).isEmpty();
        assertThat(definition.eventsFrom(CREATED)).containsExactly(PAY, CLOSE);
        assertThat(definition.eventsFrom(SHIPPED)).isEmpty();
        assertThat(definition.isTerminal(SHIPPED)).isTrue();
        assertThat(definition.isTerminal(CREATED)).isFalse();
    }

    @Test
    void should_use_always_guard_when_not_specified() {
        Transition<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> transition =
                StateMachineFixtures.orderDefinition().transition(CREATED, PAY).orElseThrow();

        assertThat(transition.guard().allow(new TransitionContext<>(CREATED, PAY, PAID))).isTrue();
    }

    @Test
    void should_keep_explicit_guard() {
        Transition<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> transition =
                StateMachineFixtures.alwaysGuardDefinition().transition(CREATED, PAY).orElseThrow();
        Transition<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> rejected =
                StateMachineFixtures.neverGuardDefinition().transition(CREATED, PAY).orElseThrow();

        assertThat(transition.guard().allow(new TransitionContext<>(CREATED, PAY, PAID))).isTrue();
        assertThat(rejected.guard().allow(new TransitionContext<>(CREATED, PAY, PAID))).isFalse();
    }

    @Test
    void should_allow_self_transition() {
        StateMachineDefinition<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> definition =
                StateMachineFixtures.selfTransitionDefinition();

        assertThat(definition.targetState(CREATED, PAY)).isEqualTo(CREATED);
        assertThat(definition.eventsFrom(CREATED)).containsExactly(PAY, CLOSE);
    }

    @Test
    void should_reject_blank_or_null_name() {
        assertThatThrownBy(() -> StateMachineDefinition.builder(" ").initial(CREATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> StateMachineDefinition.builder(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_reject_missing_initial_state() {
        assertThatThrownBy(() -> StateMachineDefinition.<StateMachineFixtures.OrderState,
                StateMachineFixtures.OrderEvent>builder("order")
                .transition(CREATED, PAY, PAID)
                .terminal(PAID)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("初始状态");
    }

    @Test
    void should_reject_terminal_initial_state() {
        assertThatThrownBy(() -> StateMachineDefinition.<StateMachineFixtures.OrderState,
                StateMachineFixtures.OrderEvent>builder("order")
                .initial(CREATED)
                .terminal(CREATED)
                .transition(PAID, PAY, PAID)
                .terminal(PAID)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态");
    }

    @Test
    void should_reject_duplicate_transition_for_same_from_and_event() {
        assertThatThrownBy(() -> StateMachineDefinition.<StateMachineFixtures.OrderState,
                StateMachineFixtures.OrderEvent>builder("order")
                .initial(CREATED)
                .transition(CREATED, PAY, PAID)
                .transition(CREATED, PAY, CLOSED)
                .terminal(PAID)
                .terminal(CLOSED)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void should_reject_state_without_outgoing_transition_that_is_not_terminal() {
        assertThatThrownBy(() -> StateMachineDefinition.<StateMachineFixtures.OrderState,
                StateMachineFixtures.OrderEvent>builder("order")
                .initial(CREATED)
                .transition(CREATED, PAY, PAID)
                .transition(PAID, SHIP, SHIPPED)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHIPPED")
                .hasMessageContaining("终态");
    }

    @Test
    void should_reject_terminal_state_with_outgoing_transition() {
        assertThatThrownBy(() -> StateMachineDefinition.<StateMachineFixtures.OrderState,
                StateMachineFixtures.OrderEvent>builder("order")
                .initial(CREATED)
                .transition(CREATED, PAY, PAID)
                .terminal(PAID)
                .transition(PAID, SHIP, SHIPPED)
                .terminal(SHIPPED)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAID")
                .hasMessageContaining("出边");
    }

    @Test
    void should_reject_unreachable_state() {
        assertThatThrownBy(() -> StateMachineDefinition.<StateMachineFixtures.OrderState,
                StateMachineFixtures.OrderEvent>builder("order")
                .initial(CREATED)
                .transition(CREATED, PAY, PAID)
                .terminal(PAID)
                .terminal(SHIPPED)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHIPPED")
                .hasMessageContaining("不可达");
    }

    @Test
    void should_reject_null_arguments() {
        assertThatThrownBy(() -> StateMachineDefinition.<StateMachineFixtures.OrderState,
                StateMachineFixtures.OrderEvent>builder("order").transition(null, PAY, PAID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("from");
        assertThatThrownBy(() -> StateMachineDefinition.<StateMachineFixtures.OrderState,
                StateMachineFixtures.OrderEvent>builder("order").initial(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StateMachineDefinition.<StateMachineFixtures.OrderState,
                StateMachineFixtures.OrderEvent>builder("order").transition(CREATED, PAY, PAID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("guard");
    }

    @Test
    void should_fail_require_transition_with_state_machine_exception() {
        StateMachineDefinition<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> definition =
                StateMachineFixtures.orderDefinition();

        assertThatThrownBy(() -> definition.requireTransition(CREATED, SHIP))
                .isInstanceOf(StateMachineException.class)
                .hasMessage("当前状态 CREATED 不允许执行操作 SHIP");
        assertThat(definition.transition(CREATED, SHIP)).isEmpty();
    }

    @Test
    void should_expose_immutable_views() {
        StateMachineDefinition<StateMachineFixtures.OrderState, StateMachineFixtures.OrderEvent> definition =
                StateMachineFixtures.orderDefinition();

        assertThatThrownBy(() -> definition.states().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.transitions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.terminalStates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.eventsFrom(CREATED).clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
