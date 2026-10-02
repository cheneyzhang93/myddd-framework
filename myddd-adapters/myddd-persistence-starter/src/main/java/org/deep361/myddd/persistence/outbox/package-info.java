/**
 * Outbox 的持久化侧协作类：代理聚合根的类型解包（本包），以及 JDBC 存储实现（Task 19 起）。
 *
 * <p>本包存在的首要理由：领域事件落 Outbox 时用的 {@code aggregateType} 与 {@code aggregateId} 来自
 * kernel 默认发布器对 {@code source.getClass()} 的直接读取，聚合一旦被 CGLIB / javassist / Hibernate
 * 代理，写出去的就是代理类名与 {@code null} id（详见 {@link org.deep361.myddd.persistence.outbox.AggregateTypes}）。
 */
package org.deep361.myddd.persistence.outbox;
