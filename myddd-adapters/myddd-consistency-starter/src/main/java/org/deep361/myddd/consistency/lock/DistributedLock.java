package org.deep361.myddd.consistency.lock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式分布式锁：方法调用被一把 Redis 锁包住，锁的获取与释放由 {@link DistributedLockAspect} 负责。
 *
 * <p><b>{@link #key()} 是 SpEL，不是字面量占位符</b>：整串按 SpEL 解析，所以常量键写单引号
 * （{@code key = "'order:create'"}）、参数键直接用参数名（{@code key = "#userId"}）、嵌套属性也行
 * （{@code key = "#order.userId"}）。选 SpEL 而不是自定义的 {@code {0}} 占位符，是因为业务真要拼键时
 * （"租户:订单号"）需要的就是表达式，而自定义语法只会多出一套只支持取参数的方言。参数名要靠
 * {@code -parameters} 编译才可见（父 POM 已全局打开），也可用位置变量 {@code #p0} 兜底。
 *
 * <p><b>{@link #waitTime()}/{@link #leaseTime()} 的字面量与配置文件同一种语言</b>：由 Boot 的
 * {@code DurationStyle} 解析（{@code 500ms}、{@code 30s}、{@code 1m}、{@code PT0.5S} 都收），
 * 与 {@code myddd.consistency.lock.watchdog-timeout} 用同一套写法，业务不必记两套时间格式。
 *
 * <p><b>缺省时序就是 {@link org.deep361.myddd.kernel.port.consistency.LockPort} 的缺省时序</b>
 * （不等、持锁 30 秒且有界不续租）。要 watchdog 显式写 {@code leaseTime = "0s"}（裁定 R3）。
 *
 * <p><b>两条 Spring AOP 的固有限制在这里是契约的一部分</b>：① 只拦公开方法上的注解，且必须<b>跨 bean
 * 调用</b>——同一个 bean 内部 {@code this.method()} 不走代理，锁不会生效；② 注解在实现类的方法上，
 * 不要只写在接口方法上（{@code @annotation} 切点看的是被调用的那个方法）。这两条不额外做检测：
 * 检测"自调用"需要扫字节码，代价与收益不成比例，而写错的表现为"锁没生效"，在压测与灰度里立刻可见。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /**
     * 锁键的 SpEL 表达式；求值结果必须非 null（空白键由端口拒绝，见 {@link DistributedLockAspect}）。
     *
     * <p>形参名以<b>注解所在的那个方法</b>为准：业务实现了接口、而接口与实现的形参不同名时，能求到值的是
     * 实现类那一套名字（切面会把 JDK 代理给出的接口方法折算到最具体方法）。
     */
    String key();

    /** 获取锁的最长等待时长，写法同配置项（如 {@code "200ms"}）；缺省不等待。 */
    String waitTime() default "0s";

    /**
     * 持锁时长，写法同配置项；缺省 30 秒且有界不续租。
     *
     * <p>{@code "0s"} 表示交给 Redisson watchdog 续租（裁定 R3），此时续租节拍取
     * {@code myddd.consistency.lock.watchdog-timeout}。
     */
    String leaseTime() default "30s";
}
