/**
 * {@code myddd-limiter-resilience4j-starter}：{@code RateLimitPort} 与 {@code CircuitBreakerPort} 的
 * Resilience4j provider。
 *
 * <p><b>与 {@code myddd-resilience-starter} 的分工</b>：{@code @RateLimit}/{@code @CircuitBreaker} 两个切面、
 * {@code myddd.resilience.*} 三个自有键与等待策略都在壳模块（Task 44）里，本包<b>只有端口实现</b>。
 * 切面调用端口、端口不认识切面，因此换一个 provider 不需要换注解，业务侧也不需要知道厂商（§0.3-6、R1）。
 *
 * <p><b>两个端口的语义边界</b>：{@code RateLimitPort.tryAcquire} 非阻塞——拿不到额度立即 {@code false}，
 * 429 由切面抛；{@code CircuitBreakerPort.execute} 在开闸期拒绝执行动作并抛
 * {@code BizException(CommonErrorCode.SERVICE_DEGRADED)}（HTTP 503），被保护动作自身的异常原样上抛。
 * 等待、退避、降级文案一律不在本模块，端口只回答"这一发能不能过"。
 *
 * <p><b>非阻塞在 r4j 侧的唯一落点</b>：{@code RateLimiter} 上没有 {@code tryAcquirePermission} 家族，
 * 能给布尔的只有 {@code acquirePermission(int)}，而它是否阻塞由配置里的 {@code timeoutDuration} 决定
 * （实测同一发超大请求：{@code timeout=5s} 阻塞 1002ms、{@code timeout=ZERO} 阻塞 0ms）。
 * ⇒ {@code timeoutDuration=ZERO} 是这条端口契约的实现级定义，由 {@code Resilience4jRulePublisher} 钉成常量
 * 并由回读核验看守，本模块不提供把它改大的键。
 */
package org.deep361.myddd.limiter.resilience4j;
