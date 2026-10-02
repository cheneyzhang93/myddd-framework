package org.deep361.myddd.persistence.it;

import org.deep361.myddd.kernel.ddd.context.Domain;

/**
 * 真库验收用例的领域装配夹具：一个空的领域模块声明。
 *
 * <p><b>为什么需要它</b>：{@code myddd-domain-starter} 在 persistence 的 compile 依赖里，它的
 * {@code domainModuleRegistry} 缺省按"应用主类所在包"扫描，扫不到任何 {@code @Domain} 就抛
 * {@code IllegalStateException}，于是本包每一条 {@code @SpringBootTest} 都在<b>建上下文</b>那一步就红了
 * ——红的原因与数据库、与 Outbox、与审计都无关，只与"这个 IT 应用没有领域模块"有关。
 *
 * <p><b>为什么补夹具而不是关掉自动装配</b>：这些用例要验收的是"一份真实应用会有的装配在真库上跑成什么样"，
 * 把 {@code MydddDomainAutoConfiguration} 用 {@code exclude} 摘掉，等于把要验收的对象改小到不需要验收；
 * 而本框架的 {@code @Domain} 模块为空是合法形态（模块名唯一即可），所以给一个空模块是最小的补法。
 *
 * <p>本类不承担证据：真库证据在 {@code OutboxMySqlIT}、{@code DeadLetterMySqlIT}、{@code AuditMySqlIT}、
 * {@code RoutingMySqlIT} 里。
 */
@Domain("persistence-it-probe")
class ItDomainModule {
}
