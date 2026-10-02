package org.deep361.myddd.limiter.sentinel.circuit;

import java.util.function.Supplier;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;
import org.deep361.myddd.limiter.sentinel.SentinelResourceNames;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * {@link CircuitBreakerPort} 的 Sentinel 实现：{@link SphU} 包住动作，失败在 {@code close} 之前记账。
 *
 * <p><b>为什么这里用 {@code SphU}（与限流端口相反）**：熔断的语义要求"动作执行完了、结果是成功还是失败"
 * 这件事被记进统计，而 {@code SphU} 的 {@code Entry} 正是那个"正在进行"的载体——{@code Tracer.trace} 需要
 * 它还在场。限流端口没有这个时刻（它在动作之前返回布尔），所以那边只能是一票即走的 {@code SphO}。
 * 两个端口用两种厂商入口不是风格不统一，而是两个契约的形状不同。
 *
 * <p><b>记账时机是这个类唯一不能挪的一行</b>：{@code Tracer.trace(failure)} 必须在 {@code entry.close()}
 * 之前执行。一手实测同一份规则、同一组三次调用，只把 {@code trace} 挪到 {@code close} 之后：
 * 挪前 {@code ran,ran,DegradeException}（第三次被开闸挡住）、挪后 {@code ran,ran,ran}（熔断永远不开）。
 * 也就是说写反以后<b>所有调用都正常放行、没有任何报错</b>，只有"下游全挂在熔断后面"这一格会在生产出现。
 * 钉子是 {@code AB7} 与 {@code AB9}，反向证据是 P45-O。
 *
 * <p><b>只把厂商的拒绝翻译成 503</b>：{@link BlockException} ⇒ {@link BizException}
 * （{@link CommonErrorCode#SERVICE_DEGRADED}，并把原 {@code BlockException} 作为 cause 带上——
 * provider 侧带根因不违反 R2，因为业务代码看不见这个类）；动作自己的异常<b>原样上抛</b>，
 * 一行都不包装。把业务 bug 翻译成降级返回是这类框架最容易掩盖故障的写法（内核契约原文）。
 *
 * <p><b>{@code catch} 的是 {@code Throwable}，不是 {@code Exception}</b>：断言失败（{@code AssertionError}）
 * 与 OOM 之外的 {@code Error} 都是"这次调用没成功"，漏掉它们会让一条一直在炸的接口在熔断器面前
 * 看起来健康。实测证据 P45-N：{@code Error} 在 {@code close} 前记账时，两次之后就开闸。
 * 记账之后原样重抛，本类不吞任何一个 {@code Throwable}。
 *
 * <p><b>前缀在这里的第二重作用</b>：{@code myddd:circuit:} 让这一个资源上只可能存在 degrade 规则
 * （flow 规则在 {@code myddd:rate:} 那一侧，见 {@code AB10}），所以"捕获到的 {@code BlockException}
 * 是限流拒绝还是熔断拒绝"这个问题在本端口不存在——不需要按异常子类分叉，也就没有分叉写错的余地。
 */
public final class SentinelCircuitBreakerPort implements CircuitBreakerPort {

    @Override
    public <T> T execute(String resource, Supplier<T> action) {
        String name = SentinelResourceNames.circuit(resource);
        Entry entry;
        try {
            entry = SphU.entry(name, EntryType.IN);
        } catch (BlockException refused) {
            throw BizException.causedBy(CommonErrorCode.SERVICE_DEGRADED, refused);
        }
        try {
            return action.get();
        } catch (RuntimeException | Error failure) {
            Tracer.trace(failure);
            throw failure;
        } finally {
            entry.close();
        }
    }
}
