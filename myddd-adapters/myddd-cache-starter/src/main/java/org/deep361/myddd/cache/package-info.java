/**
 * 缓存适配器（Spring Cache 规范之上的 Caffeine L1 与 Redis L2 两级缓存）。
 *
 * <p>子包按<b>层次</b>而不是按技术命名：{@code l1}＝进程内那一层，{@code l2}＝跨节点的 Redis 那一层，
 * {@code composite}＝把两层拼成一个 {@code CacheManager} 的自研组合件，{@code autoconfigure}＝装配与配置键。
 *
 * <p>组合件必须自研：Spring 的 {@code CompositeCacheManager} 是 first-match 语义（按顺序找到第一个含该名字的
 * 缓存就返回），拿它拼 Caffeine 与 Redis 只会得到"永远命中 L1、L2 形同虚设"。
 *
 * <p>本包不出现领域模型；业务工程通过 {@code @Cacheable} 等标准注解使用这里装配出来的管理器。
 */
package org.deep361.myddd.cache;
