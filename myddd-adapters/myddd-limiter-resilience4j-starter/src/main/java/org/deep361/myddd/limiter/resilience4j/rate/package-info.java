/**
 * {@code RateLimitPort} 的 Resilience4j 实现。
 *
 * <p><b>为什么这里没有资源名前缀</b>（对照 Sentinel 侧的 D-154，那里必须加 {@code myddd:rate:}）：
 * r4j 的限流与熔断是两张<b>不同类型</b>的注册表（{@code RateLimiterRegistry} /
 * {@code CircuitBreakerRegistry}），同一个名字写进两张表互不相干——实测两张表各放一个 {@code shared}
 * 之后限流侧 {@code true,false}、熔断侧 {@code OPEN}，反向删掉限流侧那一份时熔断仍 {@code OPEN}
 * （{@code Probe46f F8}/{@code F8b}）。Sentinel 侧必须加前缀是因为那里全局只有一张资源表，
 * 一个接口的 QPS 规则会把它的熔断语义改掉。两家因此规则文件字段名相同而资源名落点不同，
 * 这一格由 Task 47 的等价性用例正面记录。
 *
 * <p><b>为什么"没规则 ⇒ 放行且不建实例"</b>：走 {@code registry.find(name)}（不创建，实测 {@code F1} 之后
 * {@code getAllRateLimiters()} 仍是 0），而不是 {@code registry.rateLimiter(name)}——后者会用注册表缺省配置
 * 当场造一个 {@code limitForPeriod=50 / refresh=500ns / timeout=PT5S} 的实例（{@code Probe46d_5}），
 * 那既不是"不限流"，也是一条能阻塞 5 秒的后门。
 */
package org.deep361.myddd.limiter.resilience4j.rate;
