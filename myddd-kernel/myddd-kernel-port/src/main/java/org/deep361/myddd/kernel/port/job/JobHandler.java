package org.deep361.myddd.kernel.port.job;

/**
 * 任务处理器：业务代码实现的执行体，由适配器在触发时调用。
 *
 * <p>抛出的任何异常都视为任务执行失败：适配器负责记录失败并交由调度端（XXL-Job 控制台 / Spring 本地调度）
 * 决定是否重试。实现须自行保证线程安全（同一实例可能被并发触发）。
 */
@FunctionalInterface
public interface JobHandler {

    /** 执行任务。 */
    void execute(JobContext context);
}
