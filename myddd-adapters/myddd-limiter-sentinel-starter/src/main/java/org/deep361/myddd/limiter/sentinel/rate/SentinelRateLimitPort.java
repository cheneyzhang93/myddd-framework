package org.deep361.myddd.limiter.sentinel.rate;

import java.util.Objects;

import org.deep361.myddd.kernel.port.resilience.RateLimitPort;
import org.deep361.myddd.limiter.sentinel.SentinelResourceNames;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphO;

/**
 * {@link RateLimitPort} 的 Sentinel 实现：{@link SphO} 的布尔否决 + 立即 {@code exit}。
 *
 * <p><b>为什么是 {@code SphO} 而不是 {@code SphU}</b>：{@code SphU.entry} 成功时返回一个
 * {@code Entry}，它代表"这次调用正在进行"，必须等业务代码跑完再 {@code close}，线程数形态与
 * 链路统计都挂在这个 Entry 上。而端口的契约是 {@code boolean tryAcquire(…)}——它在业务代码
 * <b>之前</b>就返回了，根本没有"业务跑完"这个时刻交给它 close。所以这里只能在"拿到票就还票"
 * （{@code SphO}）与"拿不到票也留着一个不 close 的 Entry"（{@code SphU} + 忘记 close）之间选，
 * 后者是统计泄漏（P45 早期实测：不 exit 的那一条会让当前线程的上下文一直挂着）。
 *
 * <p><b>代价，说清楚</b>：立刻 {@code exit} 以后，Sentinel 的线程数形态（{@code grade=0}）
 * 在这里永远不会拒绝（P45-B 实测三次全放行），并发上限这个语义在本端口不存在。端口能表达的
 * 只有"一个时间窗内多少个请求"，所以 {@code count} 一律按 QPS 下发（{@code grade=1}），
 * 线程数形态在规则文件里直接拒收（Step 5a 的 {@code RS6} 与包说明）。
 * 这不是缺功能：把语义换成"能配但运行期不做区分"才是。
 *
 * <p><b>额度在 {@code entry} 就扣掉</b>：{@code exit(permits)} 只归还线程/统计记账，不退款
 * （P45-S 实测 {@code entry(3)} 之后 {@code exit()} 不带 count，下一次 {@code entry(1)} 仍然被拒）。
 * 因此本类没有、也不该有"业务执行失败时把许可还回去"的分支——那种写法会让一次失败调用白占额度，
 * 也会让重试风暴看起来像流量攻击。
 *
 * <p><b>入参校验在厂商调用之前</b>：{@code resource} 走 {@link SentinelResourceNames#rate(String)}
 * （空白名在那里拒），{@code permits} 在这里拒；两句都在 {@code SphO.entry} 之前，所以一次非法调用
 * 不会在 Sentinel 的统计里留下任何一条记录（{@code AB6} 钉这一格）。端口契约要求
 * {@link IllegalArgumentException}，不换成框架异常：调用方是切面，它按契约把 {@code false} 翻译成
 * 429，而把编程错误翻译成 429 就是掩盖 bug。
 *
 * <p><b>线程安全</b>：无实例状态，全部判定在 Sentinel 自己的槽（{@code ClusterNode}）里，
 * 那个结构是并发容器（端口契约要求实现线程安全，这一句是它在这里的落点）。
 */
public final class SentinelRateLimitPort implements RateLimitPort {

    /**
     * 一次申请 {@code permits} 个许可。
     *
     * @return {@code true} 放行；{@code false} 被限流（立即返回，不阻塞——等待由 Task 44 的切面按预算重试）
     * @throws IllegalArgumentException {@code resource} 为 null/空白，或 {@code permits ≤ 0}
     */
    @Override
    public boolean tryAcquire(String resource, int permits) {
        String name = SentinelResourceNames.rate(resource);
        if (permits <= 0) {
            throw new IllegalArgumentException("permits 必须为正数（RateLimitPort.tryAcquire 的契约），实际：" + permits
                    + "。0 或负数在厂商侧是“申请零个许可”，任何规则都拦不住它——"
                            + "放过去等于给业务一个永远成功的限流");
        }
        boolean acquired = SphO.entry(name, EntryType.IN, permits);
        if (acquired) {
            SphO.exit(permits);
        }
        return acquired;
    }
}
