/**
 * {@code CircuitBreakerPort} 的 Resilience4j 实现：手工三段记账。
 *
 * <p><b>为什么必须手工记账、{@code decorateSupplier} 在这一格上是坏的</b>：同一个 {@code AssertionError}
 * （{@code Error}，不是 {@code Exception}）走装饰器两次之后 {@code state=CLOSED}、
 * {@code getNumberOfFailedCalls()=0}，而且 {@code recordExceptions(Throwable.class)} 与
 * {@code recordException(pred)} 都救不了——predicate 对 {@code Error} <b>一次都没被调用</b>
 * （{@code Probe46b Q1}/{@code Q4}，{@code invocations=0}）。改成手工
 * {@code tryAcquirePermission()} → 动作 → {@code onSuccess(elapsed, NANOSECONDS)} /
 * {@code onError(elapsed, NANOSECONDS, cause)} 之后两次即 {@code OPEN}、{@code failed=2}、
 * 第三发 {@code tryAcquirePermission()=false}（{@code Q2}）。这与 Sentinel 侧"不调 {@code Tracer.trace}
 * 就根本不打开"是同一件事的两种厂商表达，因此端口形状两家同形。
 *
 * <p><b>耗时必须是 {@code System.nanoTime()} 的真差值</b>：慢调用（{@code slow-ratio}）形态完全依赖传入的
 * 耗时，把 {@code 0} 当耗时传进去这条规则永远是哑的（{@code Probe46c R1}/{@code R2}/{@code R3}）；
 * {@code getTimestampUnit()} 缺省 {@code NANOSECONDS}（{@code R0}），所以配对写法一律显式带
 * {@code TimeUnit.NANOSECONDS}。
 *
 * <p><b>重发规则会重置开闸状态</b>：热更新的唯一确定性路线是"先 {@code remove} 再建"，而新建的实例从
 * {@code CLOSED} 开始（{@code Probe46f F6}/{@code Probe46g G3b}：{@code OPEN} → {@code CLOSED}、
 * {@code tryAcquire=true}）。语义是"推一次规则等于给每个资源一次重新试探的机会"，运维必须知道这一点。
 */
package org.deep361.myddd.limiter.resilience4j.circuit;
