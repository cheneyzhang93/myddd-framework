package org.deep361.myddd.kernel.port.job;

import java.util.Set;

/**
 * 定时任务端口：任务注册表的统一入口，由适配器实现（Plan 4：xxljob / spring）。
 *
 * <p>业务代码不直接调用本端口——业务只声明 {@code @MydddJob} 并实现 {@link JobHandler}，
 * 由适配器的启动扫描器读取注解后调用 {@link #register(JobDefinition, JobHandler)}。
 *
 * <p><b>实现契约</b>：同一任务名重复注册必须快速失败（抛 {@link IllegalArgumentException}），
 * 以免静默覆盖；实现须线程安全。
 */
public interface JobPort {

    /**
     * 注册任务。
     *
     * @param definition 任务定义，不可为 {@code null}
     * @param handler    执行体，不可为 {@code null}
     * @throws IllegalArgumentException 任务名重复注册
     */
    void register(JobDefinition definition, JobHandler handler);

    /** 已注册任务名（不可变集合，用于诊断与等价性测试）。 */
    Set<String> registeredJobs();
}
