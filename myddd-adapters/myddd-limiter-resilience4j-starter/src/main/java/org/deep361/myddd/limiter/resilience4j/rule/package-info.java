/**
 * 规则面：文档模型 → 读取器 → 契约级校验器 → 唯一的热更新接缝（publisher）。
 *
 * <p><b>三道网的分工，一条都不重叠</b>：
 * <ol>
 *   <li>{@link org.deep361.myddd.limiter.resilience4j.rule.Resilience4jRuleDocumentReader} 只管形态
 *       （根是不是对象、段是不是数组、字段在不在白名单里、类型逐字对）；</li>
 *   <li>{@link org.deep361.myddd.limiter.resilience4j.rule.Resilience4jRuleValidator} 只拦
 *       "r4j 能 {@code build()} 出来但我们的端口形状用不了"的形态（{@code threshold} 不是
 *       {@code error-ratio}/{@code slow-ratio}、{@code windowMs} 不是整秒、{@code count} 缺省）；</li>
 *   <li>厂商的 {@code build()} 拦越界值（{@code limitForPeriod<=0}、{@code failureRateThreshold} 越界、
 *       {@code waitDurationInOpenState=0} 全部当场抛 {@code IllegalArgumentException}）——这与 Sentinel 侧
 *       "静默丢弃"恰好相反，所以这里不重复实现数值区间（重复一份就是把第二个真源写进框架，D-153）。</li>
 * </ol>
 * 第三道网之后还有回读核验（{@code verifyLoaded}），它拦的不是配置对不对，而是<b>我们自己的
 * "先 {@code remove} 再建"路线有没有被改坏</b>：名字集双向比对 + {@code limitForPeriod} 与
 * {@code timeoutDuration==ZERO} 逐资源核验。
 *
 * <p><b>{@code ratio=100} 这条不对称的去向</b>：Sentinel 侧接受等价的 {@code 1.0}（"全部失败才开闸"），
 * r4j 的阈值必须 {@code <100}（实测消息 {@code failureRateThreshold must be greater than 0 and lower than
 * 100}）。校验器把它拒在厂商之前并且写明不许换算成 {@code 99.99}；这一格作为跨家差异登记在偏差 D-161，
 * 由 Task 47 的等价性清单交付。
 */
package org.deep361.myddd.limiter.resilience4j.rule;
