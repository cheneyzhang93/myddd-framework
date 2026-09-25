package org.deep361.myddd.domain.outbox;

/**
 * Outbox 记录状态。
 *
 * <p>迁移路径（只能通过 {@link OutboxRecord} 的助手方法，非法迁移抛异常）：
 * <pre>
 *   PENDING ──dispatching()──▶ DISPATCHING ──dispatched()──▶ DISPATCHED（终态）
 *      ▲                             │
 *      └──────retrying()─────────────┤（可重试：回到 PENDING，带 nextRetryAt）
 *                                    └──────dead()───────▶ DEAD（终态，需人工介入）
 * </pre>
 *
 * <p><b>租约不在本枚举里</b>：{@code DISPATCHING} 的"租约到期"是存储实现的判定（见 {@link OutboxStore}），
 * 记录本身不携带租约字段——否则每次续租都要重写整行。
 */
public enum OutboxStatus {

    /** 待投递：可被投递器认领（{@code nextRetryAt <= now} 时）。 */
    PENDING,

    /** 投递中：已被某个投递器认领并持有租约；租约过期后会被重新认领（投递器崩溃恢复）。 */
    DISPATCHING,

    /** 已投递：终态，不再参与投递。 */
    DISPATCHED,

    /** 死信：重试次数耗尽仍未投出，需人工介入（保留 payload 与 lastError 供排障与重放）。 */
    DEAD
}
