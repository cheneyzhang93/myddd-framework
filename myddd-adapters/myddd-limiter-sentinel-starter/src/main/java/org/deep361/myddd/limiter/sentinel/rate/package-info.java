/**
 * 限流端口的 Sentinel 实现。
 *
 * <p>厂商入口是 {@code SphO}（布尔否决）而不是 {@code SphU}：{@code RateLimitPort.tryAcquire} 在业务
 * 代码<b>之前</b>返回，没有"业务跑完"的时刻可以用来 close 一个 Entry，而 {@code SphU} 的语义要求它。
 * 拿到票立即 {@code exit(permits)}：额度在 {@code entry} 时就扣掉了，{@code exit} 只归还线程/统计记账，
 * 不退款。所以线程数形态（{@code grade=0}）在这里永不拒绝（一手实测三次全放行），
 * 规则文件对它直接拒收；本端口能表达的只有"一个时间窗内多少个请求"。
 *
 * <p><b>不阻塞</b>：被限流立即返回 {@code false}。有界等待（{@code @RateLimit(timeout=…)}）住在
 * Task 44 的切面里，厂商侧的 {@code controlBehavior=2}（匀速排队，实测一次调用等 1009ms）在规则
 * 文件里拒收——等待语义出现在两个地方就没法算总预算。
 */
package org.deep361.myddd.limiter.sentinel.rate;
