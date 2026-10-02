/**
 * 装配面：属性、provider 二选一判定、条件、自动配置。
 *
 * <p>本包是 {@code …job.spring.config} 那一包的<b>同形第二份</b>，不是它的复制粘贴：
 * Job 能力没有装配壳（R1 只批准了 mq/resilience/auth 三个壳），于是 §0.3-6 的"同一能力只许一份自动装配产出"
 * 必须在每个 provider 模块各落地一次，互斥判定用字符串 marker 而不是类型依赖（§0.3-11）。
 * 判定类 {@link org.deep361.myddd.job.xxljob.config.JobProviderSelection} 不碰反射、不碰容器，
 * 因此 {@code PS} 组五条不需要把另一家 provider 摆上测试 classpath。
 * 这一份代码带来的代价公开登记在这里：两份同形逻辑的行为一致性由 Task 43 的 {@code EQ} 组跨模块钉住。
 */
package org.deep361.myddd.job.xxljob.config;
