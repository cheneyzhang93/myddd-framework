package org.deep361.myddd.resilience;

/**
 * {@link PollSleeper} 的生产实现：一次 {@code Thread.sleep}。
 *
 * <p>接缝存在不是为了换实现，而是为了让"预算内睡几次、每次多久"这组判据可以在零真睡的条件下逐条
 * 精确断言（{@code RL} 组 16 条全部走替身）。"装配里挂的确实是这一个"由 {@code AC8} 用<b>行为</b>证明
 * ——真睡带来的耗时下界，而不是 {@code getXxx()} 返回类型那种自证。
 *
 * <p>中断不在这里翻译：{@code InterruptedException} 原样交给 {@link RateLimitAspect}，由它恢复中断标志
 * 并停止继续索取许可（{@code RL11}）。
 */
public final class ThreadPollSleeper implements PollSleeper {

    @Override
    public void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
