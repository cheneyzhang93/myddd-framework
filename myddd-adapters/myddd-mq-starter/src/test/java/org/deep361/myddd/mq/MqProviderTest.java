package org.deep361.myddd.mq;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MqProvider#parse(String)} 是"值写错了"唯一变成一句人话的地方，本类钉它的措辞。
 */
class MqProviderTest {

    @Test
    void should_parse_case_insensitively_and_with_dashes() {
        // 大小写不敏感 + 去空白：配置手滑不该成为启动失败的理由之外的第二种错。
        assertThat(MqProvider.parse("RocketMQ")).isEqualTo(MqProvider.ROCKETMQ);
        assertThat(MqProvider.parse(" kafka ")).isEqualTo(MqProvider.KAFKA);
        assertThat(MqProvider.parse("ROCKETMQ")).isEqualTo(MqProvider.ROCKETMQ);
        assertThat(MqProvider.parse("Kafka")).isEqualTo(MqProvider.KAFKA);
    }

    @Test
    void should_reject_an_unknown_provider_listing_the_allowed_values() {
        // 诊断三段式（实际值 + 全部可取值）是框架对配置错误的承诺，缺一段就退化成一问三不知的 valueOf。
        assertThatThrownBy(() -> MqProvider.parse("rabbitmq"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rabbitmq")
                .hasMessageContaining(MqProvider.ALLOWED_VALUES)
                .hasMessageContaining("rocketmq、kafka");
    }

    @Test
    void should_reject_a_blank_provider() {
        assertThatThrownBy(() -> MqProvider.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空白");
        assertThatThrownBy(() -> MqProvider.parse("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空白")
                .hasMessageContaining(MqProvider.ALLOWED_VALUES);
    }

    @Test
    void should_name_the_module_that_provides_each_provider() {
        // 失败消息里的指路模块名必须与 Task 35/37 的构件名逐字一致，否则用户按图索骥找不到 jar。
        assertThat(MqProvider.ROCKETMQ.moduleName()).isEqualTo("myddd-mq-rocketmq-starter");
        assertThat(MqProvider.KAFKA.moduleName()).isEqualTo("myddd-mq-kafka-starter");
    }
}
