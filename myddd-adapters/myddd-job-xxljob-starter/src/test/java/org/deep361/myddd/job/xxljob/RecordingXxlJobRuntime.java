package org.deep361.myddd.job.xxljob;

import com.xxl.job.core.handler.IJobHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 录制用的执行器接缝：把"绑一个 handler / 启动 / 停机"变成可断言的事件序列。
 *
 * <p>它按厂商与真实现的语义模仿三件事，模仿得不像就会漏掉对应用例：
 * ① {@link #bind} 用 {@code Map.put}，同名<b>照单覆盖</b>（实测 S5-5/S5-6/S5-7 厂商就是这个行为）——
 * 接缝自己判重的话，端口漏判重那一类变异就测不出来；
 * ② {@link #shutdown} 会把绑定表清空，复现厂商 {@code destroy()} 先清表的行为（{@code RS11} 靠它把
 * {@code triggerNow} 引到 {@code IllegalStateException} 那一支）。
 *
 * <p>③ 停机之后 {@link #bind} 抛"已停机"——镜像 {@code DefaultXxlJobRuntime.bind} 的那条守卫：
 * {@code RS10} 要钉的是"守卫在接缝那一层、端口不另立 closed 标志"，夹具没有这条守卫，
 * 该用例就只能去碰真实现（而真实现的 {@code start()} 恰恰是全模块禁止的）。
 */
public class RecordingXxlJobRuntime implements XxlJobRuntime {

    private final Map<String, IJobHandler> bound = new LinkedHashMap<>();
    private final List<String> events = new ArrayList<>();
    private RuntimeException failureToThrow;
    private int loadCalls;
    private boolean stopped;

    @Override
    public void bind(String jobName, IJobHandler handler) {
        if (stopped) {
            throw new IllegalStateException("XXL-Job 执行器已停机，拒绝注册：" + jobName);
        }
        events.add("bind:" + jobName);
        if (failureToThrow != null) {
            RuntimeException pending = failureToThrow;
            failureToThrow = null;
            throw pending;
        }
        bound.put(jobName, handler);
    }

    @Override
    public IJobHandler load(String jobName) {
        loadCalls++;
        return bound.get(jobName);
    }

    @Override
    public void start() {
        events.add("start");
    }

    @Override
    public void shutdown() {
        events.add("shutdown");
        stopped = true;
        bound.clear();
    }

    @Override
    public boolean isStarted() {
        return events.contains("start");
    }

    /** 事件序列，形如 {@code [bind:j1, start, shutdown]}（{@code AB9} 的判据来源）。 */
    public List<String> events() {
        return List.copyOf(events);
    }

    /** 已绑定的 handler（{@code RS2} 判"执行器上已被占用"、{@code HD2} 判绑定实例就是包装层）。 */
    public IJobHandler boundHandler(String jobName) {
        return bound.get(jobName);
    }

    /** 不经 {@link #bind} 直接放进表里：模拟 {@code @XxlJob} 方法或外部代码占着同一个名字。 */
    public void bindExternally(String jobName, IJobHandler handler) {
        bound.put(jobName, handler);
    }

    /** 下一次 {@link #bind} 抛这个异常（{@code RS8}：接缝失败不许留半成品）。 */
    public void failNextBindWith(RuntimeException failure) {
        this.failureToThrow = failure;
    }

    public int loadCalls() {
        return loadCalls;
    }

    public int startCalls() {
        return (int) events.stream().filter("start"::equals).count();
    }

    public int shutdownCalls() {
        return (int) events.stream().filter("shutdown"::equals).count();
    }
}
