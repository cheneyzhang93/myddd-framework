package org.deep361.myddd.kernel.statemachine;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StateMachineExceptionTest {

    @Test
    void should_carry_illegal_state_error_code_and_rendered_message() {
        StateMachineException exception =
                new StateMachineException(StateMachineFixtures.OrderState.CREATED, StateMachineFixtures.OrderEvent.SHIP);

        assertThat(exception).isInstanceOf(BizException.class);
        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.ILLEGAL_STATE);
        assertThat(exception.getErrorCode().httpStatus()).isEqualTo(409);
        assertThat(exception.getMessage()).isEqualTo("当前状态 CREATED 不允许执行操作 SHIP");
        assertThat(exception.getMessageArgs()).containsExactly(StateMachineFixtures.OrderState.CREATED,
                StateMachineFixtures.OrderEvent.SHIP);
    }

    @Test
    void should_carry_cause_via_static_factory() {
        IllegalStateException root = new IllegalStateException("db down");

        StateMachineException exception = StateMachineException.causedBy(
                StateMachineFixtures.OrderState.PAID, StateMachineFixtures.OrderEvent.PAY, root);

        assertThat(exception.getCause()).isSameAs(root);
        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.ILLEGAL_STATE);
        assertThat(exception.getMessage()).isEqualTo("当前状态 PAID 不允许执行操作 PAY");
    }

    @Test
    void should_reject_null_error_identity() {
        StateMachineException exception = new StateMachineException(null, null);

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.ILLEGAL_STATE);
        assertThat(exception.getMessage()).isEqualTo("当前状态 null 不允许执行操作 null");
    }
}
