package org.deep361.myddd.domain.event;

import org.deep361.myddd.kernel.ddd.event.DomainEventStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * 事务阶段执行器：按 {@link DomainEventStage} 决定动作的执行时机。
 *
 * <p>{@code IN_TRANSACTION}：立即在当前线程执行，异常原样上抛（回滚由外层事务拦截器完成）。
 *
 * <p>{@code AFTER_COMMIT}：仅当当前线程"同步已激活<b>且</b>事务实际活动"时注册
 * {@link TransactionSynchronization#afterCommit()} 回调；否则<b>直接跳过</b>——既不降级为事务内执行，
 * 也不延迟到下一次事务（事件已经产生，跨事务延迟投递属于 Outbox 通道的职责）。
 *
 * <p><b>提交回调内的再次投递</b>：事务同步列表在回调触发前已被快照，触发期间新注册的钩子永远不会收到
 * {@code afterCommit}。因此在 {@code AFTER_COMMIT} 监听器内部再次投递 {@code AFTER_COMMIT}（监听器里再
 * {@code publish}）时，动作<b>立即执行</b>而非注册新钩子——此刻事务已提交，语义等价，且绝不静默丢弃。
 *
 * <p>不注册 {@code afterCompletion}：回滚时不该投递"提交后"监听器（Outbox 通道负责回滚后的重投）。
 */
public class TransactionStageExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransactionStageExecutor.class);

    /** 当前线程是否正处于本执行器登记的 afterCommit 回调中（监听器内再 publish 的识别依据）。 */
    private final ThreadLocal<Boolean> insideAfterCommit = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 执行动作：IN_TRANSACTION 立即执行，AFTER_COMMIT 登记到当前事务的提交后回调。 */
    public void execute(DomainEventStage stage, Runnable action) {
        Objects.requireNonNull(stage, "投递阶段不能为 null");
        Objects.requireNonNull(action, "监听器动作不能为 null");
        if (stage == DomainEventStage.IN_TRANSACTION) {
            action.run();
            return;
        }
        if (Boolean.TRUE.equals(insideAfterCommit.get())) {
            log.debug("AFTER_COMMIT 回调内再次投递：事务已提交，改为立即执行（新钩子不会被触发）");
            action.run();
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                insideAfterCommit.set(Boolean.TRUE);
                try {
                    action.run();
                } finally {
                    insideAfterCommit.remove();
                }
            }
        });
    }
}
