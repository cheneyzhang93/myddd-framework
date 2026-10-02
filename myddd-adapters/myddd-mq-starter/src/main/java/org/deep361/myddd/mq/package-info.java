/**
 * MQ 装配壳：按通道把 {@code MessagePort} 路由到具体 provider（R1："MQ 不是二选一，是路由"）。
 *
 * <p>本包（含 {@code route} 与 {@code config} 子包）不出现任何厂商类（R2）——它对 provider 的全部
 * 认知只有两件事：{@link org.deep361.myddd.mq.MqProvider} 枚举与
 * {@link org.deep361.myddd.mq.ProviderMessagePort} SPI。新增一个 provider = 新枚举值 + 新模块，
 * 这里的代码一行不改。
 *
 * <p>子包切分：根包＝SPI 与枚举（provider 模块唯一依赖的两个类型），
 * {@code route}＝路由表与路由器（纯领域逻辑，不经 Spring 也能测），
 * {@code config}＝配置键与自动装配（启动期快速失败的发生地）。
 */
package org.deep361.myddd.mq;
