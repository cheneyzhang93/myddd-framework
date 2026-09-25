package org.deep361.myddd.domain.event;

import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * 进程内领域事件分发器：把事件按阶段投递给冻结快照中匹配的监听器。
 *
 * <p><b>失败语义</b>：
 * <ul>
 *   <li>{@code IN_TRANSACTION}：监听器异常<b>原样上抛</b>（业务异常保持自身类型，受检异常包装为
 *       {@link IllegalStateException}），本阶段剩余监听器<b>不再执行</b>——让事务回滚，暴露不变量被破坏。</li>
 *   <li>{@code AFTER_COMMIT} 与异步任务：监听器异常<b>被隔离</b>（记 ERROR 日志后继续后续监听器），
 *       不影响已提交的事务。</li>
 *   <li>异步任务提交被拒（线程池饱和或已关闭）：记 ERROR 日志后继续，不向调用方抛异常。</li>
 * </ul>
 *
 * <p><b>异步监听器只可能落在 {@code AFTER_COMMIT}</b>（{@code async + IN_TRANSACTION} 在注册期即失败），
 * 因此异步任务总是在提交之后才提交给线程池；无事务上下文时 {@code AFTER_COMMIT} 阶段整体跳过
 * （含异步，任务不会进入线程池）。
 *
 * <p><b>快照来源</b>：构造器接收 {@link DomainEventListenerRegistrar}，在<b>每次分发时</b>读取注册器
 * 已冻结的快照。注册器要到 {@code afterSingletonsInstantiated()} 才冻结快照，而本分发器 bean 在上下文
 * 刷新期间（早于冻结）就被实例化，故构造期读取必然失败；分发发生在上下文刷新完成之后，读取到的即冻结
 * 快照（上下文未刷新完成就分发时由注册器抛 {@link IllegalStateException}）。
 */
public class DomainEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventDispatcher.class);

    private final Supplier<List<RegisteredListener>> listeners;
    private final TransactionStageExecutor stageExecutor;
    private final Executor asyncExecutor;

    public DomainEventDispatcher(DomainEventListenerRegistrar registrar, TransactionStageExecutor stageExecutor,
                                 Executor asyncExecutor) {
        this(Objects.requireNonNull(registrar, "监听器注册器不能为 null")::listeners, stageExecutor, asyncExecutor);
    }

    private DomainEventDispatcher(Supplier<List<RegisteredListener>> listeners,
                                  TransactionStageExecutor stageExecutor, Executor asyncExecutor) {
        this.listeners = Objects.requireNonNull(listeners, "监听器快照不能为 null");
        this.stageExecutor = Objects.requireNonNull(stageExecutor, "事务阶段执行器不能为 null");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "异步执行器不能为 null");
    }

    /** 把事件投递给指定阶段的所有匹配监听器（顺序即快照顺序：order → bean 名 → 方法名 → 方法签名）。 */
    public void dispatch(DomainEvent event, DomainEventStage stage) {
        Objects.requireNonNull(event, "领域事件不能为 null");
        Objects.requireNonNull(stage, "投递阶段不能为 null");
        for (RegisteredListener listener : listeners.get()) {
            if (listener.stage() != stage || !listener.supports(event.getClass())) {
                continue;
            }
            stageExecutor.execute(stage, () -> run(listener, event));
        }
    }

    private void run(RegisteredListener listener, DomainEvent event) {
        if (listener.async()) {
            submit(listener, event);
        } else if (listener.stage() == DomainEventStage.IN_TRANSACTION) {
            invokeOrPropagate(listener, event);
        } else {
            invokeOrLog(listener, event);
        }
    }

    private static void invokeOrPropagate(RegisteredListener listener, DomainEvent event) {
        try {
            listener.invoke(event);
        } catch (Throwable ex) {
            throw asUnchecked(listener, event, ex);
        }
    }

    private static void invokeOrLog(RegisteredListener listener, DomainEvent event) {
        try {
            listener.invoke(event);
        } catch (Throwable ex) {
            log.error("领域事件监听器执行失败（已隔离）：listener={}, event={}",
                    listener.describe(), event.getClass().getName(), ex);
        }
    }

    private void submit(RegisteredListener listener, DomainEvent event) {
        try {
            asyncExecutor.execute(() -> invokeOrLog(listener, event));
        } catch (RejectedExecutionException ex) {
            log.error("异步领域事件监听器提交被拒（线程池已饱和或已关闭）：listener={}, event={}",
                    listener.describe(), event.getClass().getName(), ex);
        }
    }

    /** 业务异常保持原类型上抛；{@link Error} 直接上抛；受检异常包装为 {@link IllegalStateException}。 */
    private static RuntimeException asUnchecked(RegisteredListener listener, DomainEvent event, Throwable ex) {
        if (ex instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (ex instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("领域事件监听器 " + listener.describe() + " 处理 "
                + event.getClass().getName() + " 时抛出受检异常", ex);
    }
}
