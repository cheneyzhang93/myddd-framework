package org.deep361.myddd.consistency.lock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.deep361.myddd.kernel.port.consistency.LockPort;

/**
 * 记录型 {@link LockPort}：切面测试里"落到端口的那三元组"就是判据本体。
 *
 * <p>两条从 Task 23/24 沿下来的纪律：① {@link #calls()} 是<b>有序</b>日志，"加锁在事务替身之前、
 * 解锁在其后"这类顺序本身就是被钉的东西；② 断言只读 {@link #snapshot()} 与 {@link #invocations()}
 * 的<b>副本</b>——直接暴露内部列表会让后续用例读到上一条的残留（Task 23 的 D-35 实测过这个坑）。
 *
 * <p>这里<b>不</b>模拟锁的互斥语义（那属于 {@code DefaultLockPort}，Task 24 已钉）。本类只做两件事：
 * 记录调用、执行动作。唯一例外是 {@link #rejectBlankKeys}：打开它时长空白键像真端口一样拒绝，
 * 用于钉"切面没有把空白键静默放行到端口之外"。
 */
public class RecordingLockPort implements LockPort {

    /** 一次 {@code executeLocked} 的入参，切面算出来的东西全在这三个字段里。 */
    public record Invocation(String key, Duration waitTime, Duration leaseTime) {
    }

    private final List<String> calls;
    private final List<Invocation> invocations = new ArrayList<>();

    private boolean rejectBlankKeys = true;
    private RuntimeException onFailure;

    public RecordingLockPort() {
        this(new ArrayList<>());
    }

    /**
     * 收一个<b>共享</b>日志：切面与"事务替身"通知往同一个列表里各写一笔，
     * 锁包住事务这条顺序断言才有共同的时间轴可比——两份日志各自排序再拼起来证明不了任何交错关系。
     */
    public RecordingLockPort(List<String> sharedLog) {
        this.calls = sharedLog;
    }

    public RecordingLockPort withoutBlankKeyRejection() {
        this.rejectBlankKeys = false;
        return this;
    }

    public RecordingLockPort throwingOnAcquire(RuntimeException failure) {
        this.onFailure = failure;
        return this;
    }

    public List<String> calls() {
        return List.copyOf(this.calls);
    }

    public List<Invocation> invocations() {
        return List.copyOf(this.invocations);
    }

    public Invocation last() {
        return this.invocations.get(this.invocations.size() - 1);
    }

    @Override
    public <T> T executeLocked(String key, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        if (this.onFailure != null) {
            this.calls.add("acquire:" + key);
            throw this.onFailure;
        }
        if (this.rejectBlankKeys && (key == null || key.isBlank())) {
            throw new IllegalArgumentException("锁键空白（与 DefaultLockPort 同一条拒绝）");
        }
        this.calls.add("acquire:" + key);
        this.invocations.add(new Invocation(key, waitTime, leaseTime));
        try {
            return action.get();
        } finally {
            this.calls.add("release:" + key);
        }
    }
}
