package org.deep361.myddd.kernel.statemachine;

/** 状态机夹具：标准订单状态图（含两个终态）与带守卫的定义。 */
final class StateMachineFixtures {

    private StateMachineFixtures() {
    }

    enum OrderState {
        CREATED, PAID, SHIPPED, CLOSED
    }

    enum OrderEvent {
        PAY, SHIP, CLOSE
    }

    /**
     * 标准状态图：CREATED --PAY--> PAID --SHIP--> SHIPPED(终态)；CREATED --CLOSE--> CLOSED(终态)。
     */
    static StateMachineDefinition<OrderState, OrderEvent> orderDefinition() {
        return StateMachineDefinition.<OrderState, OrderEvent>builder("order")
                .initial(OrderState.CREATED)
                .transition(OrderState.CREATED, OrderEvent.PAY, OrderState.PAID)
                .transition(OrderState.PAID, OrderEvent.SHIP, OrderState.SHIPPED)
                .terminal(OrderState.SHIPPED)
                .transition(OrderState.CREATED, OrderEvent.CLOSE, OrderState.CLOSED)
                .terminal(OrderState.CLOSED)
                .build();
    }

    /** 守卫恒真的定义：CREATED --PAY--> PAID(终态)，守卫写成显式 lambda 以断言"守卫被保存下来"。 */
    static StateMachineDefinition<OrderState, OrderEvent> alwaysGuardDefinition() {
        return StateMachineDefinition.<OrderState, OrderEvent>builder("always")
                .initial(OrderState.CREATED)
                .transition(OrderState.CREATED, OrderEvent.PAY, OrderState.PAID, context -> context.to() == OrderState.PAID)
                .terminal(OrderState.PAID)
                .build();
    }

    /** 守卫恒假：用于断言守卫拒绝。 */
    static StateMachineDefinition<OrderState, OrderEvent> neverGuardDefinition() {
        return StateMachineDefinition.<OrderState, OrderEvent>builder("never")
                .initial(OrderState.CREATED)
                .transition(OrderState.CREATED, OrderEvent.PAY, OrderState.PAID, context -> false)
                .terminal(OrderState.PAID)
                .build();
    }

    /** 自转移定义：单一状态自环，验证合法性判定不误伤自转移。 */
    static StateMachineDefinition<OrderState, OrderEvent> selfTransitionDefinition() {
        return StateMachineDefinition.<OrderState, OrderEvent>builder("self")
                .initial(OrderState.CREATED)
                .transition(OrderState.CREATED, OrderEvent.PAY, OrderState.CREATED)
                .transition(OrderState.CREATED, OrderEvent.CLOSE, OrderState.CLOSED)
                .terminal(OrderState.CLOSED)
                .build();
    }

    /** 只认识 CREATED/PAID 的"旧版本"状态机：用于模拟库里状态比当前代码新/脏时的恢复失败。 */
    static StateMachineDefinition<OrderState, OrderEvent> partialDefinition() {
        return StateMachineDefinition.<OrderState, OrderEvent>builder("partial")
                .initial(OrderState.CREATED)
                .transition(OrderState.CREATED, OrderEvent.PAY, OrderState.PAID)
                .terminal(OrderState.PAID)
                .build();
    }

    /** 可变状态持有者：模拟聚合上的状态字段（状态存在领域对象里，状态机只读写它）。 */
    static final class Holder<S> {

        private S state;

        Holder(S state) {
            this.state = state;
        }

        S get() {
            return state;
        }

        void set(S state) {
            this.state = state;
        }
    }
}
