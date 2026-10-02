package org.deep361.myddd.job.spring;

import java.time.Instant;

/**
 * 一次任务执行的失败记录（诊断与等价性用，不替代日志）。
 *
 * <p>唯一构造点是 {@code SpringJobPort.runGuarded}，三个字段在那里都必然非空（任务名来自注册表、
 * 时刻来自 {@code Instant.now()}、异常来自 {@code catch}），所以本记录不加校验：加了就是
 * 一段永远不会被打红也永远不会被打死的代码，变异表对它只能登记"无观察差异"。
 *
 * @param jobName 任务名
 * @param at      失败时刻
 * @param cause   业务执行体抛出的原始异常（<b>不包装</b>：包装会让"是哪个异常"多一层）
 */
public record JobExecutionFailure(String jobName, Instant at, Throwable cause) {
}
