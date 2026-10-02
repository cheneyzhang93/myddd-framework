package org.deep361.myddd.resilience;

/** 等待节拍接缝（Step 0 第 5 条）：把"睡多久"从"试几次"里分出来，好让次数可精确断言。 */
public interface PollSleeper {

    /** 睡 {@code millis} 毫秒；被中断时抛 {@link InterruptedException}，由切面归一。 */
    void sleep(long millis) throws InterruptedException;
}
