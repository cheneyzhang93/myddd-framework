package org.deep361.myddd.kernel.ddd.model;

import org.deep361.myddd.kernel.ddd.event.DomainEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 聚合根：实体标识语义 + 领域事件收集。
 *
 * <p>判等与哈希完全继承 {@link Entity}（{@code getClass()} 强约束 + 标识判等），本类<b>不</b>覆写二者：
 * 聚合根与同 id 的其它实体类型（含其自身父类实体）不相等。
 *
 * <p>聚合内通过 {@link #registerEvent(DomainEvent)} 收集事件；发布器在事务内发布（进程内订阅）或
 * 写入 Outbox（跨进程投递）后调用 {@link #clearDomainEvents()} 清空——两者均由领域装配模块提供
 * （Plan 2 落地）。
 *
 * <p><b>线程约束</b>：本类不是线程安全的——收集事件用未加锁的 {@code ArrayList}，且"取快照—清空"
 * 之间存在竞态；聚合实例应限定在单个请求/事务线程内使用，禁止跨线程共享。
 *
 * <p>乐观锁版本号不由本类承载：版本字段属于持久化适配器的 PO（MyBatis-Plus {@code @Version}，
 * 持久化适配器，Plan 3 落地），聚合对象只表达领域状态与事件。
 */
public abstract class AggregateRoot<ID> extends Entity<ID> {

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    /** 构造器语义同 {@link Entity}：id 在创建时确定、不可变，且不可为 null。 */
    protected AggregateRoot(ID id) {
        super(id);
    }

    /**
     * 登记一个待发布的领域事件，按登记顺序追加。
     *
     * <p>刻意声明为 {@code protected}：只有聚合自身有权决定它产生了哪些事件——由外部（应用层、仓储）
     * 代替聚合登记事件会绕过不变量校验，使事件与状态变更脱节。
     *
     * <p>{@code event} 不可为 null（{@link NullPointerException}，消息"领域事件不能为 null"）。
     *
     * @param event 待发布的领域事件，不可为 null
     */
    protected void registerEvent(DomainEvent event) {
        domainEvents.add(Objects.requireNonNull(event, "领域事件不能为 null"));
    }

    /**
     * 当前收集的领域事件：返回<b>调用时刻的不可变快照</b>（{@code List.copyOf}，按登记顺序）。
     *
     * <p>快照语义：本方法返回后，再经 {@link #registerEvent(DomainEvent)} 登记的事件<b>不会</b>出现在
     * 已返回的列表中；每次调用都基于当时的集合重新生成快照，本类不缓存返回结果，故先后两次调用的结果
     * 长度可能不同（非空集合每次得到新的列表对象，空集合由 JDK 复用同一个不可变空列表实例）。返回列表
     * 不可修改——增、删、改均抛 {@link UnsupportedOperationException}。
     *
     * <p>发布器取事件即调用本方法（领域装配模块，Plan 2 落地）。
     */
    public List<DomainEvent> domainEvents() {
        return List.copyOf(domainEvents);
    }

    /**
     * 清空已收集的领域事件（<b>破坏性</b>操作：清空全部，而非"仅清空已发布的那部分"）。
     *
     * <p>通常由发布器在<b>发布成功后</b>调用：发布失败时不清空，聚合仍持有全部事件，可在同一事务的
     * 重试或后续补偿中重新发布。
     *
     * <p><b>发布器契约</b>：本方法为无差别清空，故发布器必须先取一次快照（{@link #domainEvents()}）、
     * 以该快照为准完成发布后再调用本方法；发布期间不应再次登记事件——若监听器在"取快照—清空"之间
     * 回调 {@link #registerEvent(DomainEvent)}，这些新事件会随清空一并丢弃。需要"发布期间新产生的事件
     * 继续投递"时，由发布器循环"取快照—发布—清空"直至聚合无事件（见 {@link #drainEvents()} 与 {@link org.deep361.myddd.kernel.ddd.event.DomainEventPublisher#publish(AggregateRoot)}）。
     */
    public void clearDomainEvents() {
        domainEvents.clear();
    }

    /**
     * 取出并清空当前收集的领域事件：返回<b>调用时刻的不可变快照</b>（{@code List.copyOf}，按登记顺序），
     * 同时清空聚合内的事件——把"取快照 + 清空"合成一次动作，避免调用方漏掉清空导致事件被重复投递。
     *
     * <p><b>失败语义</b>：本方法不承诺"失败重放"——返回的事件若在投递时抛异常，聚合不会把它们收回。需要"不丢"
     * 的可靠投递走同一事务内的 Outbox 记录（事务回滚时，产生事件的状态变更也一并回滚，下一请求会基于未变更的
     * 状态重新产生事件）。
     *
     * <p>与 {@link #clearDomainEvents()} 的关系：后者是无差别清空（供"先取快照、发布成功后再清空"的两段式使用），
     * 本方法是它与 {@link #domainEvents()} 的组合；两者都保留，各有用途。
     *
     * <p>返回列表不可修改；空集合由 JDK 复用同一个不可变空列表实例。
     */
    public List<DomainEvent> drainEvents() {
        List<DomainEvent> drained = List.copyOf(domainEvents);
        domainEvents.clear();
        return drained;
    }
}
