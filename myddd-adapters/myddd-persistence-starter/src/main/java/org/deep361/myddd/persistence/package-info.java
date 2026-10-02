/**
 * 持久化适配器（MyBatis-Plus 接线、DDL 约定、多数据源路由、Outbox 的 JDBC 实现）。
 *
 * <p>本包只出现 {@code *PO} 与技术设施类；领域模型与仓储接口在业务工程的 domain 包里，由本包实现
 * （{@code MydddArchRules.persistenceObjectsShouldResideInInfrastructure} 会在业务工程编译期钉住这一点）。
 */
package org.deep361.myddd.persistence;
