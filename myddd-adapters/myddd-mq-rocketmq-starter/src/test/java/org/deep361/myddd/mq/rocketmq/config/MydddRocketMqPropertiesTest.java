package org.deep361.myddd.mq.rocketmq.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置诊断三段式（键名 + 实际值 + 期望形态），与壳的 IQ 组同一形状；不引 JSR-380（两套校验必有一套被绕过）。
 */
class MydddRocketMqPropertiesTest {

    @Test
    void should_default_the_producer_group_and_leave_the_name_server_unset() {
        MydddRocketMqProperties properties = new MydddRocketMqProperties();
        // 厂商缺省 getNamesrvAddr() 也是 null：两个"没有值"必须显式区分，缺省只给分组。
        assertThat(properties.getProducerGroup()).isEqualTo("myddd-producer");
        assertThat(properties.getNameServer()).isNull();
    }

    @Test
    void should_reject_a_missing_or_blank_name_server_with_the_three_part_diagnosis() {
        for (String illegal : new String[]{null, "  "}) {
            MydddRocketMqProperties properties = new MydddRocketMqProperties();
            properties.setNameServer(illegal);
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.mq.rocketmq.name-server")
                    .hasMessageContaining("实际：" + illegal)
                    .hasMessageContaining("ip:port")
                    // "不读厂商环境变量"这条偏离必须写在失败消息里，而不是只写在 javadoc 里。
                    .hasMessageContaining("NAMESRV_ADDR");
        }
    }

    @Test
    void should_reject_a_blank_producer_group() {
        MydddRocketMqProperties properties = new MydddRocketMqProperties();
        properties.setNameServer("127.0.0.1:9876");
        properties.setProducerGroup("  ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.mq.rocketmq.producer-group")
                .hasMessageContaining("myddd-producer");
    }

    @Test
    void should_accept_every_legal_name_server_shape() {
        // 只做非空白，不解析形态：域名、分号列表、代理端点都是合法输入（深度校验只会把用户推向关掉校验）。
        for (String legal : new String[]{"127.0.0.1:9876", "10.0.0.1:9876;10.0.0.2:9876",
                "namesrv.example.com:9876", "rocketmq-namesrv:9876"}) {
            MydddRocketMqProperties properties = new MydddRocketMqProperties();
            properties.setNameServer(legal);
            assertThatCode(properties::validate).doesNotThrowAnyException();
            assertThat(properties.getNameServer()).isEqualTo(legal);
        }
    }

    // ---- IQ5–IQ9：Task 36 的 consumer.* 三条键（同一 validate() 入口，不新开第二个校验方法）----

    /** name-server 先给上：否则三条 consumer 校验永远走不到，报错会被第一条吞掉。 */
    private static MydddRocketMqProperties namedServerProperties() {
        MydddRocketMqProperties properties = new MydddRocketMqProperties();
        properties.setNameServer("127.0.0.1:9876");
        return properties;
    }

    @Test
    void should_default_the_three_consumer_keys() {
        // IQ5：缺省值是"能从配置算出来的数"，不是厂商缺省的透传（厂商 -1=交给 broker、15=分钟）。
        MydddRocketMqProperties.Consumer consumer = namedServerProperties().getConsumer();
        assertThat(consumer.getGroup()).isEqualTo("myddd-consumer");
        assertThat(consumer.getMaxReconsumeTimes()).isEqualTo(16);
        assertThat(consumer.getConsumeTimeout()).isEqualTo(Duration.ofMinutes(15));

        // 对照：厂商自身缺省是 maxReconsumeTimes=-1（交给 broker 配置）与 consumeTimeout=15（**分钟**）；
        // 本框架只借用"15 分钟"这一条，重投上限必须显式钉住，否则同一份应用在两套 broker 上行为不同。
        assertThat(consumer.getConsumeTimeout().toMinutes()).isEqualTo(15L);
    }

    @Test
    void should_reject_a_blank_consumer_group() {
        // IQ6：这一层只管非空白（能指到键名），形态（非法字符、超长）在消费者创建时校验（能指到来源）——
        // 两处各校验一半、消息风格统一（DC1 是另一半）。
        for (String illegal : new String[]{null, "  "}) {
            MydddRocketMqProperties properties = namedServerProperties();
            properties.getConsumer().setGroup(illegal);
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.mq.rocketmq.consumer.group")
                    .hasMessageContaining("实际：" + illegal)
                    .hasMessageContaining("myddd-consumer")
                    .hasMessageContaining("厂商形态规则在消费者创建时另行校验");
        }
    }

    @Test
    void should_reject_a_negative_max_reconsume_times() {
        // IQ7：>=0 才是规则，所以边界两侧都要断（只断 -1 被拒的话，"改成 >0 才通过"也能全绿）。
        MydddRocketMqProperties rejected = namedServerProperties();
        rejected.getConsumer().setMaxReconsumeTimes(-1);
        assertThatThrownBy(rejected::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.mq.rocketmq.consumer.max-reconsume-times")
                .hasMessageContaining("实际：-1")
                .hasMessageContaining("%DLQ%")
                // 被拒的理由必须写在消息里：厂商缺省 -1 的语义是"交给 broker"，那是两套真相。
                .hasMessageContaining("厂商自身缺省是 -1=交给 broker 配置");

        for (int legal : new int[]{0, 1}) {
            MydddRocketMqProperties properties = namedServerProperties();
            properties.getConsumer().setMaxReconsumeTimes(legal);
            assertThatCode(properties::validate).doesNotThrowAnyException();
            assertThat(properties.getConsumer().getMaxReconsumeTimes()).isEqualTo(legal);
        }
    }

    @Test
    void should_reject_a_non_positive_consume_timeout() {
        // IQ8：null / 零 / 负数三种"没有有效超时"的形态都得拒，且期望形态要写清是整分钟。
        for (Duration illegal : new Duration[]{Duration.ZERO, Duration.ofMinutes(-5), null}) {
            MydddRocketMqProperties properties = namedServerProperties();
            properties.getConsumer().setConsumeTimeout(illegal);
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.mq.rocketmq.consumer.consume-timeout")
                    .hasMessageContaining("实际：" + illegal)
                    .hasMessageContaining("1m 的整数倍，如 15m、30m，缺省 15m");
        }
    }

    @Test
    void should_reject_a_sub_minute_consume_timeout_instead_of_rounding_it() {
        // IQ9：厂商单位是分钟（ProcessQueue 字节码 getConsumeTimeout()×60×1000），亚分钟会被截成 0 分钟
        // 而使每条消息都被判超时 ⇒ 拒掉并说清，而不是悄悄取整。
        for (Duration subMinute : new Duration[]{Duration.ofSeconds(90), Duration.ofSeconds(15, 500)}) {
            MydddRocketMqProperties properties = namedServerProperties();
            properties.getConsumer().setConsumeTimeout(subMinute);
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("必须是整分钟")
                    .hasMessageContaining("会被截断成 0 分钟");
        }

        // 通过的一侧：整分钟都合法（"拒掉一切亚分钟"若写成"拒掉一切"，这三条就是对照）。
        for (Duration legal : new Duration[]{Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofMinutes(30)}) {
            MydddRocketMqProperties properties = namedServerProperties();
            properties.getConsumer().setConsumeTimeout(legal);
            assertThatCode(properties::validate).doesNotThrowAnyException();
            assertThat(properties.getConsumer().getConsumeTimeout()).isEqualTo(legal);
        }
    }
}
