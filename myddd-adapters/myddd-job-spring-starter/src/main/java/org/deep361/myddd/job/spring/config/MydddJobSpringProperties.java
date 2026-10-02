package org.deep361.myddd.job.spring.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code myddd.job.*} 在 spring provider 侧的绑定（Job 无装配壳，R1 的明文范围）。
 *
 * <p>三个键各自的位置决定其职责：{@code enabled} 是总开关（关掉时本模块一个 bean 都不产，
 * 与"provider 选错"是两件事）；{@code provider} 是二选一的选中标识（歧义判定在
 * {@link JobProviderSelection}，不在这里）；{@code spring.pool-size} 只约束本模块自持的
 * {@code ThreadPoolTaskScheduler}。
 *
 * <p><b>为什么这里的校验失败抛 {@link FrameworkException} 而不是 {@code BizException}</b>：
 * 配置错属于启动期，运行期业务失败才有错误码。诊断一律三段式（键名、实际值、期望形态），
 * 由 {@code IQ} 组逐条钉住——厂商自己的报错（实测 {@code 'poolSize' must be 1 or higher}）
 * 里没有键名，用户看不到"哪个键配错了"，所以校验必须发生在递给厂商之前。
 */
@ConfigurationProperties(prefix = "myddd.job")
public class MydddJobSpringProperties {

    /** 定时任务能力的总开关。 */
    private boolean enabled = true;

    /** 选中的 provider：{@code spring} 或 {@code xxljob}；无缺省值（缺省即歧义，见 {@link JobProviderSelection}）。 */
    private String provider;

    /**
     * spring provider 自己的调参。final + 只有 getter：嵌套对象的 binder 要的是同一个实例，
     * new 在 setter 里会让构造绑定与字段绑定分叉（IQ5 用真 Binder 钉它）。
     */
    private final Spring spring = new Spring();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Spring getSpring() {
        return spring;
    }

    /**
     * 校验本 provider 用得上的那一件事：调度池大小。
     *
     * <p>只校验池大小、不碰 {@code provider}——"选谁"的唯一真源是 {@link JobProviderSelection}
     * （条件类已在读同一份 Environment 做同一判定，这里再判一次就是第二个真源，
     * 两边写法一旦分叉就会出现"条件放行、校验拒绝"这种没人能解释的启动失败）。
     *
     * @throws FrameworkException 池大小 {@code < 1}
     */
    public void validate() {
        if (spring.getPoolSize() < 1) {
            throw new FrameworkException("myddd.job.spring.pool-size 必须 >= 1（实际：" + spring.getPoolSize()
                    + "，期望形如 4）。它决定本地调度的并发上限，0 会让 ThreadPoolTaskScheduler 在启动时抛出"
                    + " 'poolSize' must be 1 or higher");
        }
    }

    /** {@code myddd.job.spring.*}。 */
    public static class Spring {

        private static final int DEFAULT_POOL_SIZE = 4;

        /** 调度线程池大小；缺省 4（厂商缺省是 1，实测 S1-P1b，不能指望它）。 */
        private int poolSize = DEFAULT_POOL_SIZE;

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }
    }
}
