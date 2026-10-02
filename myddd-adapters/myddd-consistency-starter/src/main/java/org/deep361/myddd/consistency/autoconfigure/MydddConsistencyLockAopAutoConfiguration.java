package org.deep361.myddd.consistency.autoconfigure;

import org.aspectj.lang.Aspects;
import org.deep361.myddd.consistency.lock.DistributedLock;
import org.deep361.myddd.consistency.lock.DistributedLockAspect;
import org.deep361.myddd.kernel.port.consistency.LockPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 声明式锁（{@link DistributedLock}）的装配：<b>只交出一个切面 bean</b>。
 *
 * <p><b>为什么是独立的顶层装配类，而不是塞进 {@link MydddConsistencyAutoConfiguration} 的嵌套分支</b>：
 * 那个类的类级条件是 {@code @ConditionalOnClass(RedissonClient.class)}，切面若寄在里面，
 * "不用 Redisson、自带 {@link LockPort} 实现"的应用就拿不到切面——注解会静默不生效。
 * 声明面（注解 + 切面）与实现面（Redisson 客户端 + {@code DefaultLockPort}）的条件本来就不该相同。
 *
 * <p><b>条件里没有 {@code myddd.consistency.lock.enabled}</b>：那个开关管的是"框架要不要建 Redisson 锁实现"
 * （见 {@link MydddConsistencyAutoConfiguration}），关掉它之后业务自带 {@code LockPort} 时
 * {@code @DistributedLock} 依然应当可用。切面对"有没有 LockPort"的回答在<b>调用时</b>给出
 * （{@code DistributedLockAspect#requireLockPort()}），而不是启动时靠 {@code @ConditionalOnBean} 猜——
 * 后者要求装配顺序声明，而顺序在 {@code ApplicationContextRunner} 下测不到（Task 24 要点 17 的同一课）。
 *
 * <p><b>条件里也没有 {@code @ConditionalOnMissingBean(DistributedLockAspect.class)}</b>：切面不是替换点，
 * 替换点是 {@link LockPort}（那条已经由 Task 24 的条件覆盖）。给一个没人会替换的东西开让位分支，
 * 得到的是一条没有证据可取的静默路径。
 *
 * <p><b>框架不声明 {@code @EnableAspectJAutoProxy}</b>：与缓存侧"框架不加 {@code @EnableCaching}"同一条裁定
 * ——开不开代理是应用的决定。这里的依赖是 Boot 的 {@code AopAutoConfiguration}（一手实测：它的 AspectJ
 * 分支带 {@code @ConditionalOnClass(EnableAspectJAutoProxy.class)} 且属性缺省为开），任何引了
 * {@code spring-boot-starter} 的应用都满足。若应用显式关掉了 AOP，注解不生效，这一点写在
 * {@link DistributedLock} 的 javadoc 里；不做启动期"扫全部 bean 找注解"的检测——那要遍历每个 bean 的每个方法，
 * 换来的只是替应用复查它自己刚做的决定。
 */
@AutoConfiguration
@ConditionalOnClass(Aspects.class)
@ConditionalOnProperty(prefix = "myddd.consistency", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MydddConsistencyLockAopAutoConfiguration {

    @Bean
    DistributedLockAspect mydddDistributedLockAspect(ObjectProvider<LockPort> lockPorts) {
        return new DistributedLockAspect(lockPorts);
    }
}
