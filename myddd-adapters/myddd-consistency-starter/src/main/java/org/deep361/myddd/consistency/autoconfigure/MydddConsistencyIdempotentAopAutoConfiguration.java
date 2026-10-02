package org.deep361.myddd.consistency.autoconfigure;

import org.aspectj.lang.Aspects;
import org.deep361.myddd.consistency.idempotent.IdempotentAspect;
import org.deep361.myddd.kernel.port.consistency.IdempotentPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 声明式幂等（{@code @Idempotent}）的装配：<b>只交出一个切面 bean</b>。
 *
 * <p><b>为什么与 {@link MydddConsistencyLockAopAutoConfiguration} 分开</b>：两个切面的条件都是
 * {@code myddd.consistency.enabled}（总开关），<b>都不认</b>各自那一条 {@code *.enabled}——后者管的是
 * "框架要不要建这份实现"（见 {@link MydddConsistencyIdempotentAutoConfiguration} 与
 * {@link MydddConsistencyAutoConfiguration}）。合成一个装配类省下的是一个 bean 方法，代价是
 * "总开关之下的两条能力再没法各自说明自己为什么不生效"，而这两个类各自还要为分开写一段条件论证。
 *
 * <p><b>为什么与存储装配（{@link MydddConsistencyIdempotentAutoConfiguration}）分开</b>：与 Task 24/25
 * 在锁上立的裁定同一条——声明面的条件不该继承实现面的条件。存储侧要 {@code StringRedisTemplate} 在场、
 * 要 {@code store=redis}；声明面一样都不要。业务自带 {@code IdempotentPort}（例如内存版给单测、
 * 或自己接了一张幂等表）时，注解必须照旧生效，缺口的报话在<b>调用时</b>由
 * {@code IdempotentAspect#requireIdempotentPort()} 给出，而不是启动时靠 {@code @ConditionalOnBean} 猜——
 * 后者要求装配顺序声明，而顺序在 {@code ApplicationContextRunner} 下测不到（Task 24 要点 17 的同一课）。
 *
 * <p><b>条件里没有 {@code @ConditionalOnMissingBean(IdempotentAspect.class)}</b>：切面不是替换点，
 * 替换点是 {@link IdempotentPort}。与 {@code MydddConsistencyLockAopAutoConfiguration} 同一条裁定。
 *
 * <p><b>框架不声明 {@code @EnableAspectJAutoProxy}</b>：与锁侧同一条裁定（开不开代理是应用的决定），
 * 因此 {@code Idempotent} 的 javadoc 义务里那条"自调用不走代理"在这里同样成立。
 */
@AutoConfiguration
@ConditionalOnClass(Aspects.class)
@ConditionalOnProperty(prefix = "myddd.consistency", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MydddConsistencyIdempotentAopAutoConfiguration {

    /**
     * 没有方法级条件：与 {@code mydddDistributedLockAspect} 同一条裁定。{@code idempotent.enabled=false}
     * 关掉的是框架的占位存储，不是"注解从此没人读"——把切面一起摘掉会得到幂等最坏的失效形态
     * （注解标着、一次都没拦、不报错也不日志），而留着切面时缺端口在<b>第一次调用</b>就说清了。
     */
    @Bean
    IdempotentAspect mydddIdempotentAspect(ObjectProvider<IdempotentPort> idempotentPorts) {
        return new IdempotentAspect(idempotentPorts);
    }
}
