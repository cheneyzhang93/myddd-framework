/**
 * 多数据源路由（Task 17）：命名数据源的线程键上下文、{@link org.deep361.myddd.persistence.routing.
 * RoutingDataSource} 与 {@link org.deep361.myddd.persistence.routing.UseDataSource} 切面。
 *
 * <p>本包不依赖 {@code autoconfigure} 包：装配在 {@code MydddRoutingDataSourceAutoConfiguration} 里，
 * 反过来由装配引用实现。
 */
package org.deep361.myddd.persistence.routing;
