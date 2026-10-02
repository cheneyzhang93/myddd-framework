/**
 * Sentinel provider 的根包。
 *
 * <p><b>资源命名</b>：本包内的所有厂商调用都走 {@code SentinelResourceNames} 这一个点，
 * 限流资源实名 {@code myddd:rate:<业务 key>}、熔断资源实名 {@code myddd:circuit:<业务 key>}。
 * 业务侧（注解 {@code key}、规则文件里的 {@code resource}、诊断消息）一律是裸名。
 * 前缀是必需的而不是修饰：Sentinel 的资源表是进程级全局命名空间，同名裸资源会让一个接口的
 * QPS 规则改掉它的熔断语义（拒绝原因从 503 变成 429，web 侧的处置完全不同）。
 *
 * <p><b>控制台（dashboard）不在本框架的配置面里</b>：没有任何 {@code myddd.limiter.*} 键用来开关
 * 上报。机制是 {@code sentinel-transport-*} 不在 classpath 上，于是
 * {@code HeartbeatSenderInitFunc} / {@code CommandCenterInitFunc} 这两条 SPI 根本不加载。
 * 要接控制台就自己引 transport 并按三方启动参数配（{@code -Dcsp.sentinel.dashboard.server=…}、
 * {@code -Dcsp.sentinel.api.port=…}、{@code -Dproject.name=…}）——那些键不是 myddd 的配置面，
 * 本框架不翻译也不代理（§0.3-5）。
 *
 * <p><b>本包不引 transport、不引 datasource</b>：见模块 POM。
 */
package org.deep361.myddd.limiter.sentinel;
