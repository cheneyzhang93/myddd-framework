package org.deep361.myddd.kernel.ddd.model.support;

import org.deep361.myddd.kernel.ddd.event.AbstractDomainEvent;
import org.deep361.myddd.kernel.ddd.model.AggregateRoot;

/**
 * 跨包测试夹具：真实业务聚合多定义在各自业务包中，其 {@code registerEvent} 调用发生在本包之外。
 * 若 {@code registerEvent} 被降级为包私有或私有，本类编译失败——以此在编译期钉住 protected 可见性。
 */
public class ExternalOrder extends AggregateRoot<Long> {

    public ExternalOrder(Long id) {
        super(id);
    }

    public void pay() {
        registerEvent(new ExternalOrderPaidEvent());
    }

    public static class ExternalOrderPaidEvent extends AbstractDomainEvent {
    }
}
