package org.deep361.myddd.consistency.lock;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 记录型 Redisson 夹具：只实现 {@link DefaultLockPort} 真正会调到的那几个方法，<b>其余调用一律让测试红</b>。
 *
 * <p>为什么不用 mock 框架、也不手写实现类（一手实测的规模问题）：{@code RedissonClient} 有
 * <b>233 个抽象方法</b>、{@code RLock} 连继承来的共 <b>36 个</b>（{@code javap} 计数），手写实现类
 * 会把测试文件变成一坨样板；而 mock 框架"未打桩的调用返回 null"恰好是最危险的形状——
 * 锁代码拿到 {@code null} 只会 NPE 在别处，看起来像被测代码的 bug。这里把未审查的调用直接抛成
 * {@link AssertionError}，让"端口用了我们没审过的 API"写在失败消息里。
 *
 * <p>用 {@link Proxy} 是因为两个目标都是接口，代理不需要知道一共有多少方法。
 */
public final class RecordingLockClient {

    private final List<String> interactions = new ArrayList<>();
    private final List<String> requestedLockNames = new ArrayList<>();

    /** {@code tryLock} 的返回值：{@code false} 用来演"锁被别人占着"。 */
    private boolean lockAcquired = true;

    /** {@code isHeldByCurrentThread} 的返回值：{@code false} 用来演"租约在动作期间到期"。 */
    private boolean heldByCurrentThread = true;

    /** 非空时 {@code tryLock} 抛它，用来演"等待期间线程被中断"。 */
    private Throwable tryLockFailure;

    private final RedissonClient client;
    private final RLock lock;

    public RecordingLockClient() {
        ClassLoader loader = getClass().getClassLoader();
        client = (RedissonClient) Proxy.newProxyInstance(loader, new Class<?>[] {RedissonClient.class},
                (proxy, method, args) -> dispatch(proxy, "RedissonClient", method, args));
        lock = (RLock) Proxy.newProxyInstance(loader, new Class<?>[] {RLock.class},
                (proxy, method, args) -> dispatch(proxy, "RLock", method, args));
    }

    RecordingLockClient lockAcquired(boolean acquired) {
        this.lockAcquired = acquired;
        return this;
    }

    RecordingLockClient heldByCurrentThread(boolean held) {
        this.heldByCurrentThread = held;
        return this;
    }

    RecordingLockClient tryLockFailing(Throwable failure) {
        this.tryLockFailure = failure;
        return this;
    }

    public RedissonClient client() {
        return client;
    }

    RLock lock() {
        return lock;
    }

    /** 逐条交互日志，形如 {@code getLock(myddd:lock:order:1)}、{@code tryLock(0,30000,MILLISECONDS)}。 */
    List<String> interactions() {
        return List.copyOf(interactions);
    }

    /** 端口向客户端要过哪些锁名（按调用顺序）。 */
    List<String> requestedLockNames() {
        return List.copyOf(requestedLockNames);
    }

    int countOf(String interaction) {
        return (int) interactions.stream().filter(interaction::equals).count();
    }

    private Object dispatch(Object proxy, String api, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            // 代理会把 equals/hashCode/toString 也送进 handler；AssertJ 打印失败上下文时真的会调 toString，
            // 未审查的 API 断言不能把这些噪音算成端口的依赖面。
            return switch (method.getName()) {
                case "toString" -> "Recording" + api;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> unknown(api, method, args);
            };
        }
        String name = method.getName();
        if ("RedissonClient".equals(api)) {
            if ("getLock".equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == String.class) {
                String lockName = (String) args[0];
                requestedLockNames.add(lockName);
                interactions.add("getLock(" + lockName + ")");
                return lock;
            }
        } else {
            switch (name) {
                case "tryLock" -> {
                    if (method.getParameterCount() == 2) {
                        // tryLock(long, TimeUnit)：java.util.concurrent.locks.Lock 继承来的那个重载，
                        // 在 Redisson 里就是"不给租约、由 watchdog 续租"的形态（R3 的第二条）。
                        return attemptLock("tryLock(" + args[0] + "," + args[1] + ")");
                    }
                    if (method.getParameterCount() == 3) {
                        return attemptLock("tryLock(" + args[0] + "," + args[1] + "," + args[2] + ")");
                    }
                }
                case "unlock" -> {
                    interactions.add("unlock()");
                    return null;
                }
                case "isHeldByCurrentThread" -> {
                    interactions.add("isHeldByCurrentThread()");
                    return heldByCurrentThread;
                }
                default -> {
                }
            }
        }
        return unknown(api, method, args);
    }

    private Object attemptLock(String log) throws Throwable {
        interactions.add(log);
        if (tryLockFailure != null) {
            throw tryLockFailure;
        }
        return lockAcquired;
    }

    private Object unknown(String api, Method method, Object[] args) {
        throw new AssertionError("DefaultLockPort 调用了未在夹具里审查过的 " + api + " 方法："
                + method.getName() + Arrays.toString(method.getParameterTypes())
                + "。端口对 Redisson 的依赖面必须保持可读的尺寸——扩夹具之前先想清楚这个调用是否必要。");
    }
}
