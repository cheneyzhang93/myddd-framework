package org.deep361.myddd.job.spring;

/**
 * {@link SpringJobPort} 的调度接缝：把"任务名 + 执行体 + cron 表达式"交给某个调度器。
 *
 * <p>存在的唯一理由是<b>可测性</b>：端口的注册、校验、包装、失败记录、停机五件事必须能在
 * 零线程、零等待的情况下断言（{@code RS}/{@code FA} 组共 16 条，一条都不 sleep），
 * 而真调度只由 {@code FL} 组三条用 {@link DefaultCronTaskScheduler} 证明"确实接上了"。
 *
 * <p>它是 public 的只为让装配面（{@code config} 包）能引用这一接缝（偏差 D-142 修正初稿的"包私有"），
 * 但<b>不</b>挂 {@code @ConditionalOnMissingBean}：允许业务贡献自己的调度器就等于
 * 允许 {@code pool-size} 这个键变成一句空话（§0.3-6 的"同一能力只有一份自动装配产出"）。
 */
public interface CronTaskScheduler {

    /**
     * 登记一个按 {@code cron} 周期执行的任务。
     *
     * @param jobName 任务名（仅用于诊断，调度器不必存储）
     * @param task    已包装好的执行体（失败处理在包装层，见 {@link SpringJobPort}）
     * @param cron    已通过校验的 6 段表达式
     */
    void schedule(String jobName, Runnable task, String cron);

    /** 停机：之后 {@code schedule} 必须失败，不允许静默丢弃任务。 */
    void shutdown();
}
