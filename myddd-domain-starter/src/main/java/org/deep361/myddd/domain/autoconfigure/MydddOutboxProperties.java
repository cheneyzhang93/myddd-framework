package org.deep361.myddd.domain.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox 配置：{@code myddd.domain.outbox.*}。
 *
 * <p>默认关闭：置 true 后每次发布领域事件都必须在事务内，且上下文必须提供
 * {@link org.deep361.myddd.domain.outbox.OutboxStore} 实现，否则启动失败（不允许"打开了可靠投递开关却静默不记录"）。
 *
 * <p>投递器相关配置（重试次数、批量大小、轮询间隔、租约、退避）在 Task 17 追加到本类。
 */
@ConfigurationProperties(prefix = "myddd.domain.outbox")
public class MydddOutboxProperties {

    /** 是否启用 Outbox 可靠投递（同事务记录 + 异步投递）；默认 false。 */
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
