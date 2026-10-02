/**
 * Job 能力的 XXL-Job 适配器：编程式注册到执行器，触发时机由调度中心下发。
 *
 * <p>四条边界：① 本包只引用 {@code myddd-kernel-*} 的类型、Spring 与 {@code com.xxl.job.core}，
 * 出现 spring 侧适配器的任何类型即为设计失败（§0.3-11）；② <b>cron 在本侧只声明、不生效</b>，
 * 因此本包不校验表达式语法（执行器 jar 里没有 cron 入口，§0.1 实测零命中；校验会抹平 R7 承认的不对称），
 * 这条不对称由 Task 43 的等价性组<b>显式不覆盖</b>并登记；③ 执行器生命周期由本包的
 * {@link org.deep361.myddd.job.xxljob.XxlJobRuntime} 自持，不用厂商的 {@code XxlJobSpringExecutor}，
 * 也不用它的 {@code setEnabled(false)} 当测试出路（未启动的实例 {@code destroy()} 必 NPE、
 * {@code enabled=false} 仍写静态字段，两条都是一手读数 S5-3/S5-4 与 S5-1/S5-2）；
 * ④ {@link org.deep361.myddd.job.xxljob.XxlJobRuntime} 是<b>公开</b>接口而 spring 侧的同类接缝是包私有的：
 * 本侧"注册"之后必须有人启动，一启动就是真线程 + 真端口 + 真注册心跳，单测的一条都不许碰到那三样，
 * 所以这扇门必须开到 {@code config} 子包注入得进来。
 */
package org.deep361.myddd.job.xxljob;
