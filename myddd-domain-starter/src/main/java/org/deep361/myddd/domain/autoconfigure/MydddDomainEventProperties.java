package org.deep361.myddd.domain.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 领域事件配置：{@code myddd.domain.event.*}。 */
@ConfigurationProperties(prefix = "myddd.domain.event")
public class MydddDomainEventProperties {

    private final Async async = new Async();

    public Async getAsync() {
        return async;
    }

    /** 异步监听器线程池配置（有界队列；配置非法即启动失败）。 */
    public static class Async {

        /** 核心线程数。 */
        private int corePoolSize = 4;

        /** 最大线程数。 */
        private int maxPoolSize = 16;

        /** 有界队列容量（必须 ≥ 1：事件的产生速度不应由无界队列兜底）。 */
        private int queueCapacity = 1000;

        /** 非核心线程空闲存活时间。 */
        private Duration keepAlive = Duration.ofSeconds(60);

        /** 上下文关闭时等待在飞监听任务完成的上限（必须 ≥ 1s：线程池等待粒度是整秒）。 */
        private Duration shutdownTimeout = Duration.ofSeconds(30);

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public Duration getKeepAlive() {
            return keepAlive;
        }

        public void setKeepAlive(Duration keepAlive) {
            this.keepAlive = keepAlive;
        }

        public Duration getShutdownTimeout() {
            return shutdownTimeout;
        }

        public void setShutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
        }

        /** 校验配置合法性；非法配置直接抛异常（启动失败），不静默回退默认值。 */
        public void validate() {
            if (corePoolSize < 1) {
                throw new IllegalStateException("myddd.domain.event.async.core-pool-size 必须 ≥ 1，实际 " + corePoolSize);
            }
            if (maxPoolSize < corePoolSize) {
                throw new IllegalStateException("myddd.domain.event.async.max-pool-size 必须 ≥ core-pool-size（"
                        + corePoolSize + "），实际 " + maxPoolSize);
            }
            if (queueCapacity < 1) {
                throw new IllegalStateException("myddd.domain.event.async.queue-capacity 必须 ≥ 1（有界队列，"
                        + "避免事件无上限积压），实际 " + queueCapacity);
            }
            if (keepAlive == null || keepAlive.toSeconds() < 1) {
                throw new IllegalStateException("myddd.domain.event.async.keep-alive 必须 ≥ 1s，实际 " + keepAlive);
            }
            if (shutdownTimeout == null || shutdownTimeout.toSeconds() < 1) {
                throw new IllegalStateException("myddd.domain.event.async.shutdown-timeout 必须 ≥ 1s（线程池等待"
                        + "粒度是整秒，小于 1s 会被截断为 0，等于静默关闭\"关闭时等待在飞任务\"），实际 " + shutdownTimeout);
            }
        }
    }
}
