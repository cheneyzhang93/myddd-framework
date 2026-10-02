/**
 * {@code myddd.resilience.*} 三个自有键与其启动期校验。
 *
 * <p>键表与语义的唯一出处是计划正文 §1.2.1；本包不做任何三方键的翻译：限流/熔断的阈值与规则归
 * provider 自己的配置面（{@code myddd.limiter.*}、{@code resilience4j.*}、Sentinel 规则文件），
 * 在这里重复一遍就会有两个真源（§0.3-5）。
 */
package org.deep361.myddd.resilience.config;
