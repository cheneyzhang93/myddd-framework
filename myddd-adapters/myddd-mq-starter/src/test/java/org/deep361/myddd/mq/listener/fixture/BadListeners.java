package org.deep361.myddd.mq.listener.fixture;

import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageListener;

/**
 * 测试夹具（非用例）：六条非法签名，一条一个内部静态类，供 LS6–LS11 <b>逐个单独扫</b>。
 *
 * <p>为什么要拆开：注册表按"本类 → 父类 → 接口"遍历方法，多条非法签名挤在一个类里时，
 * 谁先抛取决于反射返回顺序——那会让每条用例断言的消息变成偶然结果。
 */
public final class BadListeners {

    private BadListeners() {
    }

    /** 静态方法：装配层拿不到可调用的实例语义。 */
    public static class StaticProbe {

        @MessageListener(channel = "bad-static")
        public static void on(InboundMessage message) {
        }
    }

    /** private 方法：装配层需要能调用它。 */
    public static class PrivateProbe {

        @MessageListener(channel = "bad-private")
        private void on(InboundMessage message) {
        }
    }

    /** 返回值非 void：消费结果由"抛不抛异常"表达，返回值会被静默丢弃。 */
    public static class NonVoidProbe {

        @MessageListener(channel = "bad-non-void")
        public String on(InboundMessage message) {
            return "ignored";
        }
    }

    /** 零参数：没有任何一种形态能填它。 */
    public static class NoArgumentProbe {

        @MessageListener(channel = "bad-no-argument")
        public void on() {
        }
    }

    /** 两参数：分发器只会传一个实参。 */
    public static class TwoArgumentProbe {

        @MessageListener(channel = "bad-two-arguments")
        public void on(InboundMessage message, String ignored) {
        }
    }

    /** 空白 channel：订阅关系由它决定，留空等于"不知道往哪投"。 */
    public static class BlankChannelProbe {

        @MessageListener(channel = "")
        public void on(InboundMessage message) {
        }
    }
}
