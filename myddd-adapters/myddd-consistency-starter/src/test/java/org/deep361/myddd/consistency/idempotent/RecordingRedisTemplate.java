package org.deep361.myddd.consistency.idempotent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 记录型的 {@link StringRedisTemplate}：把"端口到底发了哪些 Redis 命令"变成可以直接断言的证据。
 *
 * <p><b>为什么离线测试只需要到"命令"这一层</b>：本类能证明的是"发出的命令是 {@code SET NX PX}（一条命令，
 * 不是两条）、键名前缀怎么拼、释放走的是脚本而不是先读后删"。<b>不能</b>证明的是 Redis 对这些命令的实际反应
 * （NX 到底撞不撞、Lua 的比对对不对）——那是 {@code IdempotentRedisIT} 在真 Redis 上的义务（§0.3-7：
 * 离线用例证明形状，IT 证明语义，两边不重复也不互相顶替）。
 *
 * <p><b>{@link ValueOperations} 用 JDK 动态代理而不是手写实现</b>：那个接口有几十个工作方法，手写实现会
 * 把"端口其实只该用一条命令"这条判据稀释成"实现了整个接口的空壳"。代理的处理器只认
 * {@code setIfAbsent}，<b>其他任何方法当场 {@code AssertionError}</b>——于是"偷偷加了 {@code get} 预判"、
 * "改成 {@code expire} 补 TTL"这类改动会在<b>第一条</b>相关用例里红，而且红话说的是方法名。
 *
 * <p><b>记录的是被调用的方法名</b>：{@code setIfAbsent(K,V)}、{@code (K,V,long,TimeUnit)} 与
 * {@code (K,V,Duration)} 三个重载都能放进同一个 {@code SET}，只有方法名能把"没有 TTL 的那一种"区分出来。
 * 断言 {@code "setIfAbsent"} 加三元组，就同时钉住了"用了哪个重载"和"值与 TTL 原样传下去"。
 */
public class RecordingRedisTemplate extends StringRedisTemplate {

    /** 一次 Redis 交互：操作名 + 入参快照。 */
    public record Call(String operation, List<Object> arguments) {
    }

    private final List<Call> calls = new ArrayList<>();

    private Boolean setIfAbsentResult = Boolean.TRUE;
    private Long releaseResult = 1L;

    /** 让下一次 {@code setIfAbsent} 给出这个答案（{@code FALSE}＝键已存在，{@code null}＝没有即时结果）。 */
    public RecordingRedisTemplate answering(Boolean setIfAbsentResult) {
        this.setIfAbsentResult = setIfAbsentResult;
        return this;
    }

    /** 让释放脚本返回这个删除数（0＝token 不一致或占位已不在）。 */
    public RecordingRedisTemplate releasingWith(Long deleted) {
        this.releaseResult = deleted;
        return this;
    }

    public List<Call> calls() {
        return List.copyOf(this.calls);
    }

    /** 最后一次交互的入参；没有交互时让断言直接指出"一条命令都没发"。 */
    public List<Object> lastArguments() {
        if (this.calls.isEmpty()) {
            throw new AssertionError("一条 Redis 命令都没发出去，断言无从取值");
        }
        return this.calls.get(this.calls.size() - 1).arguments();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ValueOperations<String, String> opsForValue() {
        return (ValueOperations<String, String>) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),
                new Class<?>[] {ValueOperations.class}, new ValueOperationsRecorder());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... arguments) {
        this.calls.add(new Call("executeScript",
                List.of(script.getScriptAsString(), keys, Arrays.asList(arguments))));
        return (T) this.releaseResult;
    }

    /** 只认 {@code setIfAbsent}，其他方法名一律当场失败。 */
    private final class ValueOperationsRecorder implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (!"setIfAbsent".equals(method.getName())) {
                throw new AssertionError("RedisIdempotentStore 不该调用 ValueOperations." + method.getName()
                        + "(" + Arrays.toString(method.getParameterTypes()) + ")：占位只需要一条 SET NX PX，"
                        + "多出来的任何读写都说明实现把一次命令拆成了两步");
            }
            RecordingRedisTemplate.this.calls.add(new Call(method.getName(), Arrays.asList(arguments)));
            return RecordingRedisTemplate.this.setIfAbsentResult;
        }
    }
}
