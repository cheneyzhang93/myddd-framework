package org.deep361.myddd.job.xxljob;

import com.xxl.job.core.handler.IJobHandler;

/**
 * XXL-Job 执行器的接缝：注册表读写 + 生命周期。
 *
 * <p><b>为什么它是公开接口</b>（spring 侧的同类接缝 {@code CronTaskScheduler} 是包私有的）：本模块的
 * 自动配置在 {@code …xxljob.config} 子包里，那里要能把一个录制用的实现递进来；而 {@code ThreadPoolTaskScheduler}
 * 那条路上"注册"不会起线程，本侧"注册"之后必须有人调 {@link #start()}，一调就是真线程 + 真端口 + 真的
 * 30 秒注册心跳。单测里 58 条用例一条都不许碰到那三样，所以这扇门必须开到 {@code config} 子包外面。
 *
 * <p><b>次序契约</b>：{@link #bind(String, IJobHandler)} 全部发生在 {@link #start()} 之前（由
 * {@link MydddXxlJobScanner} 保证，{@code AB9} 钉）。反过来的形态是真实的失败：调度中心在启动后
 * 几毫秒就能下发触发，而 {@code JobThread} 是按名字从执行器表里取 handler 的，取不到就直接失败。
 *
 * <p>本接口一次实现一台 JVM 只允许有一个已启动的实例：厂商 {@code start()} 的第一条字节码就把自己的
 * {@code this} 写进静态字段 {@code XxlJobExecutor.xxlJobExecutor}，而 {@code JobThread} 有 8 处走
 * {@code getInstance()}（实测 S5-2/S5-21/S5-22）。
 */
public interface XxlJobRuntime {

    /**
     * 把 handler 绑到执行器上。
     *
     * @throws IllegalStateException 本接缝已 {@link #shutdown()}
     */
    void bind(String jobName, IJobHandler handler);

    /** 按名字读已绑定的 handler；没绑过返回 {@code null}（实测 S5-8）。 */
    IJobHandler load(String jobName);

    /** 启动执行器；重复调用是幂等的。 */
    void start();

    /** 停机；只有真 {@link #start()} 过才会把销毁递给厂商，重复调用幂等。 */
    void shutdown();

    /** 是否已经把启动递给过厂商（<b>不</b>表示厂商启动成功，见 {@link DefaultXxlJobRuntime#start()}）。 */
    boolean isStarted();
}
