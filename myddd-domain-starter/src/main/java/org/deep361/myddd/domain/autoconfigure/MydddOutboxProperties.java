package org.deep361.myddd.domain.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Outbox 配置：{@code myddd.domain.outbox.*}。
 *
 * <p>默认关闭：置 true 后每次发布领域事件都必须在事务内，且上下文必须提供
 * {@link org.deep361.myddd.domain.outbox.OutboxStore}（落库介质）与
 * {@link org.deep361.myddd.kernel.port.message.MessagePort}（消息适配器），否则启动失败——不允许
 * "打开了可靠投递开关却静默不记录 / 不投出"。
 *
 * <p>所有取值都会在装配时 {@link #validate()}：非法配置直接让上下文启动失败并指明<b>配置项全名</b>，
 * 不静默回退默认值（否则线上看到的"重试 5 次"可能并不是配置里的那个数字）。
 */
@ConfigurationProperties(prefix = "myddd.domain.outbox")
public class MydddOutboxProperties {

    /** 是否启用 Outbox 可靠投递（同事务记录 + 异步投递）；默认 false。 */
    private boolean enabled;

    /** 单轮最多认领 / 投递条数。 */
    private int batchSize = 50;

    /** 单条记录的最大投递尝试次数（含首次发送）；用尽仍未成功即进死信。 */
    private int maxAttempts = 5;

    /** 认领租约时长：投递器崩溃后租约过期，记录可被重新认领。 */
    private Duration leaseDuration = Duration.ofSeconds(30);

    /** 投递循环的轮询间隔。 */
    private Duration pollInterval = Duration.ofSeconds(5);

    /** 容器关闭时等待投递循环停止的上限。 */
    private Duration shutdownTimeout = Duration.ofSeconds(10);

    private final Backoff backoff = new Backoff();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public Backoff getBackoff() {
        return backoff;
    }

    /** 校验全部取值；非法配置抛异常（启动失败），消息中带配置项全名。 */
    public void validate() {
        if (batchSize < 1) {
            throw new IllegalStateException("myddd.domain.outbox.batch-size 必须 ≥ 1，实际 " + batchSize);
        }
        if (maxAttempts < 1) {
            throw new IllegalStateException("myddd.domain.outbox.max-attempts 必须 ≥ 1，实际 " + maxAttempts);
        }
        if (leaseDuration == null || leaseDuration.toMillis() < 1) {
            throw new IllegalStateException("myddd.domain.outbox.lease-duration 必须 ≥ 1ms，实际 " + leaseDuration);
        }
        if (pollInterval == null || pollInterval.toMillis() < 1) {
            throw new IllegalStateException("myddd.domain.outbox.poll-interval 必须 ≥ 1ms，实际 " + pollInterval);
        }
        if (shutdownTimeout == null || shutdownTimeout.isNegative()) {
            throw new IllegalStateException("myddd.domain.outbox.shutdown-timeout 不能为负数，实际 " + shutdownTimeout);
        }
        backoff.validate();
    }

    /** 投递失败退避配置（{@code myddd.domain.outbox.backoff.*}）。 */
    public static class Backoff {

        /** 首次失败后的等待时长。 */
        private Duration initial = Duration.ofSeconds(1);

        /** 每次失败的放大倍数（必须 ≥ 1.0；等于 1.0 表示不放大）。 */
        private double multiplier = 2.0;

        /** 等待时长上限。 */
        private Duration max = Duration.ofMinutes(1);

        /** 只向下抖动的比例（{@code [0, 1)}）；0 表示不抖动，默认 0.2 用于错开惊群。 */
        private double jitterRatio = 0.2;

        public Duration getInitial() {
            return initial;
        }

        public void setInitial(Duration initial) {
            this.initial = initial;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMax() {
            return max;
        }

        public void setMax(Duration max) {
            this.max = max;
        }

        public double getJitterRatio() {
            return jitterRatio;
        }

        public void setJitterRatio(double jitterRatio) {
            this.jitterRatio = jitterRatio;
        }

        /** 校验退避配置；非法配置抛异常并指明配置项全名。 */
        public void validate() {
            if (initial == null || initial.toMillis() < 1) {
                throw new IllegalStateException("myddd.domain.outbox.backoff.initial 必须 ≥ 1ms，实际 " + initial);
            }
            if (Double.isNaN(multiplier) || multiplier < 1.0) {
                throw new IllegalStateException("myddd.domain.outbox.backoff.multiplier 必须 ≥ 1.0，实际 " + multiplier);
            }
            if (max == null || max.compareTo(initial) < 0) {
                throw new IllegalStateException("myddd.domain.outbox.backoff.max 必须 ≥ initial（" + initial + "），实际 "
                        + max);
            }
            if (Double.isNaN(jitterRatio) || jitterRatio < 0.0 || jitterRatio >= 1.0) {
                throw new IllegalStateException("myddd.domain.outbox.backoff.jitter-ratio 必须在 [0.0, 1.0) 区间，实际 "
                        + jitterRatio);
            }
        }
    }
}
