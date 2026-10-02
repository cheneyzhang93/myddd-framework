/**
 * 装配面：属性、provider 二选一判定、条件、自动配置、注解扫描。
 *
 * <p>{@link org.deep361.myddd.job.spring.config.JobProviderSelection} 是这个包存在的唯一理由：
 * Job 能力没有装配壳（R1 只批准了 mq/resilience/auth 三个壳），"同一能力只许一份装配"这条
 * §0.3-6 的规矩必须在每个 provider 模块各自落地一次。判定写成不碰反射、不碰容器的纯函数，
 * 于是 {@code PS} 组五条不需要把另一家 provider 摆上 classpath，而两侧行为一致性由 Task 43 的
 * {@code EQ} 组跨模块钉住（那是这个设计付出的代价，公开登记在这里而不是藏进 {@code private} 里）。
 */
package org.deep361.myddd.job.spring.config;
