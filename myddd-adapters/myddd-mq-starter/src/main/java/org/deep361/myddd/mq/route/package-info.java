/**
 * 通道路由：{@link org.deep361.myddd.mq.route.MessageRouteTable}（精确通道名优先、未命中用缺省）与
 * {@link org.deep361.myddd.mq.route.RoutingMessagePort}（框架对外的唯一 {@code MessagePort}）。
 *
 * <p>这里没有 Spring：路由与失败措辞是纯逻辑，启动期装配（config 包）只是它的一个调用方。
 * 不做前缀匹配、不做通配符——通道名同时是两侧的 topic，猜错一个字符就是往另一个 topic 发消息。
 */
package org.deep361.myddd.mq.route;
