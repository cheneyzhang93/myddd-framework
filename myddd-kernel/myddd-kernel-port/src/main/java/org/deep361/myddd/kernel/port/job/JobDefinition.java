package org.deep361.myddd.kernel.port.job;

import java.util.Objects;

/**
 * 任务定义：任务名 + 调度表达式 + 说明。
 *
 * <p>{@code cron} 使用 6 段 Quartz 风格表达式（Spring 与 XXL-Job 的调度表达式语法一致）；
 * XXL-Job 适配器在控制台已配置调度周期时忽略本字段（以控制台为准），Spring 适配器以本字段注册本地调度。
 *
 * @param name        任务名，不可为空白；同一应用内不得重复
 * @param cron        调度表达式，不可为空白
 * @param description 说明，可为 {@code null}（规范化为空串）
 */
public record JobDefinition(String name, String cron, String description) {

    public JobDefinition {
        Objects.requireNonNull(name, "name 不能为 null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空白");
        }
        Objects.requireNonNull(cron, "cron 不能为 null");
        if (cron.isBlank()) {
            throw new IllegalArgumentException("cron 不能为空白");
        }
        description = description == null ? "" : description;
    }

    /** 无说明的任务定义。 */
    public static JobDefinition of(String name, String cron) {
        return new JobDefinition(name, cron, "");
    }
}
