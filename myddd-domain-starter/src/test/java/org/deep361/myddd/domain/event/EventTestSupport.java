package org.deep361.myddd.domain.event;

import org.deep361.myddd.domain.context.DomainModule;
import org.deep361.myddd.domain.context.DomainModuleRegistry;
import org.deep361.myddd.domain.event.fixture.EventModuleFixture;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** 领域事件测试支撑：模块注册表、注册器冻结、事务模拟。 */
public final class EventTestSupport {

    /** 夹具包：被领域模块 event-fixture 覆盖。 */
    public static final String FIXTURE_PACKAGE = "org.deep361.myddd.domain.event.fixture";

    private EventTestSupport() {
    }

    /** 只含夹具模块的注册表（等价于自动配置扫描出的事件夹具模块）。 */
    public static DomainModuleRegistry fixtureModules() {
        return DomainModuleRegistry.of(List.of(
                new DomainModule("event-fixture", List.of(FIXTURE_PACKAGE), EventModuleFixture.class)));
    }

    /** 构造并冻结注册器（等价于上下文刷新完成）。 */
    public static DomainEventListenerRegistrar freeze(ListableBeanFactory beanFactory) {
        DomainEventListenerRegistrar registrar = new DomainEventListenerRegistrar(beanFactory, fixtureModules());
        registrar.afterSingletonsInstantiated();
        return registrar;
    }

    /** 模拟开启事务：同步激活 + 事务活动。 */
    public static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    /** 模拟只激活同步、无实际事务（PROPAGATION_SUPPORTS 且无事务的场景）。 */
    public static void beginSynchronizationWithoutTransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    /** 模拟提交：先取出回调再清理线程状态，最后按注册顺序触发 afterCommit。 */
    public static void commitTransaction() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }

    /** 模拟回滚：只触发 afterCompletion(STATUS_ROLLED_BACK)，不触发 afterCommit。 */
    public static void rollbackTransaction() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        synchronizations.forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }

    /** 清理线程局部事务状态（每个测试结束调用，避免泄漏到下一个测试）。 */
    public static void resetTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
