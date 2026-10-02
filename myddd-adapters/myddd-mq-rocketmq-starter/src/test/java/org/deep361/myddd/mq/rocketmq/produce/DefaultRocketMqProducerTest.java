package org.deep361.myddd.mq.rocketmq.produce;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.ServiceState;
import org.apache.rocketmq.common.message.Message;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 厂商 producer 的配置、实例名唯一化与惰性启动账（全部离线：计数/失败替身顶掉真 start）。
 */
class DefaultRocketMqProducerTest {

    private static final Message WIRE = new Message("order-events", "{}".getBytes());

    @Test
    void should_configure_the_producer_from_the_two_keys() {
        DefaultRocketMqProducer producer =
                DefaultRocketMqProducer.of("10.0.0.1:9876;10.0.0.2:9876", "g-app");
        assertThat(producer.producer().getProducerGroup()).isEqualTo("g-app");
        assertThat(producer.producer().getNamesrvAddr()).isEqualTo("10.0.0.1:9876;10.0.0.2:9876");
        // 惰性启动的唯一证据：of() 只构造，厂商状态仍是"刚构造"。
        assertThat(producer.producer().getDefaultMQProducerImpl().getServiceState())
                .isEqualTo(ServiceState.CREATE_JUST);
    }

    @Test
    void should_give_each_producer_a_process_unique_instance_name() {
        String pid = String.valueOf(ProcessHandle.current().pid());
        DefaultRocketMqProducer first = DefaultRocketMqProducer.of("127.0.0.1:9876", "g-a");
        DefaultRocketMqProducer second = DefaultRocketMqProducer.of("127.0.0.1:9876", "g-b");
        assertThat(first.producer().getInstanceName()).startsWith(pid + "#");
        assertThat(first.producer().getInstanceName()).isNotEqualTo("DEFAULT");
        // 同一 JVM 两个生产者共用 clientId 下的注册表是实测形态，实例名递增后缀把它们分开。
        assertThat(first.producer().getInstanceName()).isNotEqualTo(second.producer().getInstanceName());
    }

    @Test
    void should_start_the_producer_exactly_once_for_many_sends() throws Exception {
        CountingVendorProducer vendor = new CountingVendorProducer();
        DefaultRocketMqProducer producer = new DefaultRocketMqProducer(vendor);
        producer.send(WIRE);
        producer.send(WIRE);
        assertThat(vendor.startCount).isEqualTo(1);
    }

    @Test
    void should_translate_a_start_failure_naming_group_and_name_server() {
        // 实测厂商 start() 对空白组抛 MQClientException("the specified group is blank")——真替身，无需 broker。
        DefaultMQProducer vendor = new DefaultMQProducer("  ");
        vendor.setNamesrvAddr("127.0.0.1:19876");
        DefaultRocketMqProducer producer = new DefaultRocketMqProducer(vendor);
        assertThatThrownBy(() -> producer.send(WIRE))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("producer-group=")
                .hasMessageContaining("name-server=")
                .hasCauseInstanceOf(MQClientException.class);
    }

    @Test
    void should_retry_the_start_after_a_failure_instead_of_caching_it() throws Exception {
        OnceFailVendorProducer vendor = new OnceFailVendorProducer();
        DefaultRocketMqProducer producer = new DefaultRocketMqProducer(vendor);
        assertThatThrownBy(() -> producer.send(WIRE)).isInstanceOf(FrameworkException.class);
        assertThatCode(() -> producer.send(WIRE)).doesNotThrowAnyException();
        // 钉"失败不缓存"：一次抖动不许永久废掉这台生产者。
        assertThat(vendor.startCount).isEqualTo(2);
    }

    @Test
    void should_shut_down_a_producer_that_was_never_started() {
        // 上下文启动失败时 Spring 会对已创建的 bean 挨个回调销毁：销毁不抛，才不掩盖原始异常（壳 AC2 的前提）。
        DefaultRocketMqProducer producer = DefaultRocketMqProducer.of("127.0.0.1:19876", "g-pr6");
        assertThatCode(producer::destroy).doesNotThrowAnyException();
        assertThatCode(producer::destroy).doesNotThrowAnyException();
    }

    /** 计数替身：start 只记账、send 直接回执，全程不碰网络。 */
    private static final class CountingVendorProducer extends DefaultMQProducer {
        int startCount;

        CountingVendorProducer() {
            super("g-counting");
        }

        @Override
        public synchronized void start() {
            startCount++;
        }

        @Override
        public SendResult send(Message message) {
            return RecordingRocketMqProducer.result(SendStatus.SEND_OK);
        }
    }

    /** 一次性失败替身：第一次 start 抛，之后放行——PR5 用它钉"失败不缓存、下次重试"。 */
    private static final class OnceFailVendorProducer extends DefaultMQProducer {
        int startCount;

        OnceFailVendorProducer() {
            super("g-once-fail");
        }

        @Override
        public synchronized void start() throws MQClientException {
            startCount++;
            if (startCount == 1) {
                throw new MQClientException(1, "simulated start failure");
            }
        }

        @Override
        public SendResult send(Message message) {
            return RecordingRocketMqProducer.result(SendStatus.SEND_OK);
        }
    }
}
