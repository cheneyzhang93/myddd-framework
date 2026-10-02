package org.deep361.myddd.job.spring;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * {@link CronTaskScheduler} 的真实现：自持一台 {@code ThreadPoolTaskScheduler}。
 *
 * <p>三条由一手实测决定的写法（读数在计划正文的取证表里，编号 S1/S2 可复跑）：
 * <ol>
 *   <li><b>惰性 initialize</b>：构造只做配置，第一次 {@code schedule} 才启动线程池。厂商在未初始化时
 *       {@code getScheduledExecutor()}/{@code schedule(..)} 一律抛
 *       {@code IllegalStateException: ThreadPoolTaskScheduler not initialized}（S1-P1、S2-Q1），
 *       所以"注册了任务但一台都没启动"这种半状态必须由我们自己挡住，而不是让厂商异常冒到业务面前。
 *       与 Task 35 {@code RocketMqProducer} 的惰性启动同形，{@code initialize} 只发生一次（S2-Q1 之后
 *       再调是幂等的，S1-P6）。</li>
 *   <li><b>线程名前缀 {@code myddd-job-}</b>：厂商缺省前缀让栈里分不清是谁的线程（实测前缀生效为
 *       {@code probe-1}，S1-P4b），运维只靠线程名就要能指认"这是框架的调度线程"。</li>
 *   <li><b>停机不等待</b>：{@code shutdown()} 走厂商缺省（不置 {@code waitForTasksToCompleteOnShutdown}）。
 *       本框架不把它做成配置键——没实测过的语义不进交付面；需要优雅停机的是应用，它自己贡献
 *       {@code SmartLifecycle} 或换掉端口即可（README 的运维段写明，Task 59）。</li>
 * </ol>
 *
 * <p>{@code poolSize} 校验过才递进去：厂商是在 {@code initialize()} 才拒绝 {@code 0}
 * （S1-P5 的 {@code IllegalArgumentException: 'poolSize' must be 1 or higher}），
 * 那句报错里没有配置键名。
 */
public final class DefaultCronTaskScheduler implements CronTaskScheduler {

    private final ThreadPoolTaskScheduler scheduler;
    private boolean started;
    private boolean shutdown;

    public DefaultCronTaskScheduler(int poolSize) {
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(poolSize);
        this.scheduler.setThreadNamePrefix("myddd-job-");
    }

    @Override
    public synchronized void schedule(String jobName, Runnable task, String cron) {
        if (shutdown) {
            throw new IllegalStateException("调度器已停机，任务 " + jobName + " 不会被安排");
        }
        if (!started) {
            scheduler.initialize();
            started = true;
        }
        scheduler.schedule(task, new CronTrigger(cron));
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
        // 未启动过时厂商的 destroy() 也是安全的（S1-P7 读数 NO-EXCEPTION），这里不额外判 started。
        scheduler.shutdown();
    }
}
