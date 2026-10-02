/**
 * {@code @RateLimit} 与 {@code @CircuitBreaker} 的执行侧（装配壳，零厂商依赖）。
 *
 * <p><b>注解必须写在实现类上</b>：切点按"方法上有注解或所属<b>类</b>上有注解"开门，注解只写在
 * <b>接口</b>的方法或类型上时门不开（一手实测：切点零命中，而反射读得到注解）。后果是"注解标着、
 * 一次都没拦、不报错也不日志"——这是本包唯一的静默失效形态，{@code RL15} 把它钉成可观察事实。
 * 框架不做启动期全 bean 扫描去替应用复查这个决定（与 Plan 3 锁侧的裁定同一条理由）。
 *
 * <p><b>自调用不过切面</b>：目标对象内部用 {@code this} 调一个带注解的兄弟方法不会被拦（探针 F2 实测
 * {@code HITS=[guarded]} 只一条）。需要在同类里让注解生效，请注入自身代理。
 *
 * <p><b>fallback 里的注解不生效</b>：降级方法在<b>裸目标</b>上反射调用，所以它头上的
 * {@code @RateLimit}/{@code @CircuitBreaker} 都不会触发（{@code CB11} 钉这一条，理由是不这样做会
 * "拒绝→找 fallback→fallback 再被同一条注解拒绝"的递归）。fallback 需要限流/熔断，请同样走自身代理。
 *
 * <p><b>阈值不下发</b>：{@code @CircuitBreaker} 的 {@code failureRateThreshold}/{@code slidingWindowSize}/
 * {@code waitDurationInOpenState} 在 {@code CircuitBreakerPort#execute(resource, action)} 上没有参数通道，
 * 本包只做形态校验（{@code CB15}）。真正的阈值写在各 provider 的规则面：Sentinel 规则文件、
 * {@code resilience4j.circuitbreaker.*}。这与 {@code @RateLimit.permits} 不同——那个数端口收得下、真的生效。
 *
 * <p><b>关掉切面是唯一的静默形态</b>：{@code myddd.resilience.enabled=false} 之后注解一次都不会被拦，
 * 既不报错也不日志。缺端口（provider 没引）<b>不是</b>静默形态，第一次调用就给诊断（{@code RL14}）。
 */
package org.deep361.myddd.resilience;
