package org.deep361.myddd.mq.listener.fixture;

import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试夹具（非用例）：扫描形状与失败形态——继承/覆写、同通道多条、group 冲突、包私有方法、
 * 代理 bean 的宿主类型、抛异常的监听方法。每一条都只是"被扫的对象"，被测行为全在用例里。
 */
public final class ShapeListeners {

    private ShapeListeners() {
    }

    /** 一条注解都没有的 bean（LS2：不参与注册）。 */
    public static class Plain {

        public void notAListener() {
        }
    }

    /** 注解声明在基类上（LS3 的前半与"最具体优先"的对照组）。 */
    public static class BaseHook {

        @MessageListener(channel = "hook-channel", group = "g-hook", description = "基类声明的钩子")
        public void onHook(InboundMessage message) {
        }
    }

    /** 继承但不覆写：注册的仍是基类方法。 */
    public static class InheritingHook extends BaseHook {
    }

    /** 同签名覆写且再标注一次：两条候选只留最具体的那条。 */
    public static class OverridingHook extends BaseHook {

        @Override
        @MessageListener(channel = "hook-channel", group = "g-hook", description = "子类覆写的钩子")
        public void onHook(InboundMessage message) {
        }
    }

    /** 同一通道上的两条方法（LS12 的排序对象、AB13 的调用顺序对象）。 */
    public static class SameChannelPair {

        public static final String CHANNEL = "pair-channel";

        private final List<String> invocations = new CopyOnWriteArrayList<>();

        @MessageListener(channel = CHANNEL, group = "g-pair", description = "同通道第一条")
        public void onA(InboundMessage message) {
            invocations.add("onA");
        }

        @MessageListener(channel = CHANNEL, group = "g-pair", description = "同通道第二条")
        public void onB(InboundMessage message) {
            invocations.add("onB");
        }

        public List<String> invocations() {
            return List.copyOf(invocations);
        }
    }

    /** 另一个通道上的一条方法（与 {@link SameChannelPair} 合成 LS14 的"三方法两通道"）。 */
    public static class SingleChannel {

        public static final String CHANNEL = "single-channel";

        @MessageListener(channel = CHANNEL, group = "g-single", description = "另一个通道")
        public void onSingle(InboundMessage message) {
        }
    }

    /** 与 {@link ProbeListener} 撞在同一通道上的另一个组（LS13）。 */
    public static class ConflictingGroup {

        @MessageListener(channel = ProbeListener.INBOUND_CHANNEL, group = "g-other", description = "第二个组")
        public void onConflict(InboundMessage message) {
        }
    }

    /** 包私有的监听方法：宿主类公开、方法不公开，钉住 {@code makeAccessible}（LS18）。 */
    public static class PackagePrivateHook {

        public static final String CHANNEL = "package-private-channel";

        private final List<InboundMessage> seen = new CopyOnWriteArrayList<>();

        @MessageListener(channel = CHANNEL, group = "g-pp", description = "包私有方法")
        void onPackagePrivate(InboundMessage message) {
            seen.add(message);
        }

        public List<InboundMessage> seen() {
            return List.copyOf(seen);
        }
    }

    /**
     * 三种异常形态各一条（AB9/AB10/AB11）。
     *
     * <p>异常实例是 {@code static final}：用例要 {@code assertSame} 才能证明"传播的是监听器里那一个实例"，
     * 而不是分发器新造的一个。三条分处三个通道，逐个 dispatch 才互不干扰。
     */
    public static class ThrowingHook {

        public static final String RUNTIME_CHANNEL = "throw-runtime";
        public static final String ERROR_CHANNEL = "throw-error";
        public static final String CHECKED_CHANNEL = "throw-checked";

        public static final RuntimeException RUNTIME = new IllegalStateException("listener-runtime");
        public static final AssertionError ERROR = new AssertionError("listener-error");
        public static final Exception CHECKED = new Exception("listener-checked");

        @MessageListener(channel = RUNTIME_CHANNEL, group = "g-throw", description = "抛 runtime")
        public void onRuntime(InboundMessage message) {
            throw RUNTIME;
        }

        @MessageListener(channel = ERROR_CHANNEL, group = "g-throw", description = "抛 error")
        public void onError(InboundMessage message) {
            throw ERROR;
        }

        @MessageListener(channel = CHECKED_CHANNEL, group = "g-throw", description = "抛受检异常")
        public void onChecked(InboundMessage message) throws Exception {
            throw CHECKED;
        }
    }

    /** 同通道两条、排序靠前的那条抛（AB12：fail-fast，第二条一次都不调用）。 */
    public static class FailFirstPair {

        public static final String CHANNEL = "fail-first-channel";

        private final List<String> invocations = new CopyOnWriteArrayList<>();

        @MessageListener(channel = CHANNEL, group = "g-fail", description = "第一条就抛")
        public void onA(InboundMessage message) {
            throw new IllegalStateException("first-listener-failed");
        }

        @MessageListener(channel = CHANNEL, group = "g-fail", description = "第二条")
        public void onB(InboundMessage message) {
            invocations.add("onB");
        }

        public List<String> invocations() {
            return List.copyOf(invocations);
        }
    }

    /** CGLIB 代理的宿主类（LS4：代理不改变"这条方法有人听"，且切面照常生效）。 */
    public static class InterceptedProbe {

        public static final String CHANNEL = "cglib-channel";

        private final List<InboundMessage> seen = new CopyOnWriteArrayList<>();

        @MessageListener(channel = CHANNEL, group = "g-cglib", description = "被拦截的监听")
        public void onIntercepted(InboundMessage message) {
            seen.add(message);
        }

        public List<InboundMessage> seen() {
            return List.copyOf(seen);
        }
    }

    /** JDK 代理形态下 bean 的对外类型（LS5）：注解不在这里，在实现类上。 */
    public interface OrderApi {

        void onApi(InboundMessage message);
    }

    /** 实现类标注解：注册表按接口取类型时看不见它——这条边界与 domain 侧同形，LS5 断的就是"扫不到"。 */
    public static class InterfaceOnlyProbe implements OrderApi {

        @Override
        @MessageListener(channel = "api-channel", group = "g-api", description = "只在实现类上标注")
        public void onApi(InboundMessage message) {
        }
    }
}
