/**
 * 框架表（Outbox、幂等占位）的 schema 约定：参考 DDL 的资源位置，以及 {@code OutboxRecord} 到列名的映射。
 *
 * <p>本包交付"可复制、可核对"的建表样板，不在应用启动时替业务执行迁移；迁移序列与落库位置属于业务工程。
 */
package org.deep361.myddd.persistence.ddl;
