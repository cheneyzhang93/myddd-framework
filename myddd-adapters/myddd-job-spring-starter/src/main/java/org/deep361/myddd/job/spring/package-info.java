/**
 * Job 能力的 spring 本地调度适配器：{@code CronTrigger} + {@code ThreadPoolTaskScheduler}。
 *
 * <p>三条边界：① 本包只引用 {@code myddd-kernel-*} 的类型与 Spring，出现 XXL-Job 类即为设计失败；
 * ② cron 的<b>准点性不在本包的断言面</b>（{@code RS}/{@code FA} 组一条都不 sleep，真触发时机属各家
 * 调度器，见 R7 与 Task 43 的不对称清单）；③ {@code ThreadPoolTaskScheduler} <b>不作为 bean 暴露</b>——
 * 它在 initialize 之后的 {@code getPoolSize()} 会读回 0（一手实测），把它挂进容器等于把一个会说谎的
 * getter 交给运维，需要线程池指标的人应直接拿 Spring 自己的 {@code TaskSchedulingAutoConfiguration}。
 */
package org.deep361.myddd.job.spring;
