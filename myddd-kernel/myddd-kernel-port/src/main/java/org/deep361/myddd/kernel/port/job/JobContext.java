package org.deep361.myddd.kernel.port.job;

import java.util.Objects;

/**
 * 任务执行上下文。
 *
 * @param jobName 任务名（与 {@code @MydddJob#name()} 一致）
 * @param param   调度端传入的任务参数，可为 {@code null}（规范化为空串）
 */
public record JobContext(String jobName, String param) {

    public JobContext {
        Objects.requireNonNull(jobName, "jobName 不能为 null");
        if (jobName.isBlank()) {
            throw new IllegalArgumentException("jobName 不能为空白");
        }
        param = param == null ? "" : param;
    }

    /** 无参数的任务上下文。 */
    public static JobContext of(String jobName) {
        return new JobContext(jobName, "");
    }
}
