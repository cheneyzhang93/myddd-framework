package org.deep361.myddd.job.spring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 录制用的调度接缝：把"安排一个 cron"变成"记下来并可手动触发"。
 *
 * <p>存在理由是一条硬要求：{@code RS}/{@code FA}/{@code SG} 三组 23 条不许起线程。端口在测试里
 * 因此可以被逐条 Driving——{@link #fire(String)} 就是"cron 到点"这件事的替身，它跑的是
 * {@code register} 递给我们那个 {@code Runnable}，包装层、失败记录、参数形态全都是真的。
 *
 * <p>{@code schedule} 里用 {@code Map.put} 而不判重：<b>判重是端口的职责</b>（{@code RS1}–{@code RS3}），
 * 接缝再判一次就会把"端口漏判重"这一类变异测不出来——接缝把重复安排照单收下，端口漏判时注册表就会
 * 真的被覆盖，用例当场变红。
 */
final class RecordingCronTaskScheduler implements CronTaskScheduler {

    private final Map<String, Runnable> tasks = new LinkedHashMap<>();
    private final Map<String, String> crons = new LinkedHashMap<>();
    private final List<String> scheduleOrder = new ArrayList<>();
    private int shutdownCalls;
    private RuntimeException failureToThrow;

    @Override
    public void schedule(String jobName, Runnable task, String cron) {
        if (failureToThrow != null) {
            RuntimeException pending = failureToThrow;
            failureToThrow = null;
            throw pending;
        }
        tasks.put(jobName, task);
        crons.put(jobName, cron);
        scheduleOrder.add(jobName);
    }

    @Override
    public void shutdown() {
        shutdownCalls++;
    }

    /** 模拟一次 cron 触发：执行 {@code register} 当时递给接缝的那个执行体。 */
    void fire(String jobName) {
        Runnable task = tasks.get(jobName);
        if (task == null) {
            throw new IllegalArgumentException("接缝上没有这个任务：" + jobName + "，已安排：" + tasks.keySet());
        }
        task.run();
    }

    /** 下一次 {@link #schedule} 抛这个异常（{@code RS8}：调度失败不许留半成品）。 */
    void failNextScheduleWith(RuntimeException failure) {
        this.failureToThrow = failure;
    }

    List<String> scheduleOrder() {
        return List.copyOf(scheduleOrder);
    }

    String cronOf(String jobName) {
        return crons.get(jobName);
    }

    int shutdownCalls() {
        return shutdownCalls;
    }

    boolean hasTask(String jobName) {
        return tasks.containsKey(jobName);
    }
}
