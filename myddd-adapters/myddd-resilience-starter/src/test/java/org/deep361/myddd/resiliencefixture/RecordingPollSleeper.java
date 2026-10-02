package org.deep361.myddd.resiliencefixture;

import java.util.ArrayList;
import java.util.List;

import org.deep361.myddd.resilience.PollSleeper;

/** 等待节拍的替身：把"睡了多久"变成一张可逐字断言的清单，于是 {@code RL} 组一条都不真睡。 */
public final class RecordingPollSleeper implements PollSleeper {

    /** 每次睡眠申请的毫秒数，顺序即申请顺序。 */
    public final List<Long> intervals = new ArrayList<>();

    /** 第几次睡眠申请抛中断（1 起）；0 表示从不抛。 */
    public int interruptOnSleep = 0;

    @Override
    public void sleep(long millis) throws InterruptedException {
        this.intervals.add(millis);
        if (this.interruptOnSleep == this.intervals.size()) {
            throw new InterruptedException("夹具中断");
        }
    }
}
