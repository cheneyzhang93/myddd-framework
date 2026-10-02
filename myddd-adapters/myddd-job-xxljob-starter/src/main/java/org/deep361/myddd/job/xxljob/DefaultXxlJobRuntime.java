package org.deep361.myddd.job.xxljob;

import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link XxlJobRuntime} 的真实现：自持一个 {@link XxlJobExecutor}。
 *
 * <p><b>不用 {@code XxlJobSpringExecutor}</b>：它多做的只有"扫 {@code @XxlJob} 方法"（本计划的入口是
 * 类级 {@code @MydddJob}，R7），却把生命周期绑死在 Spring 的 {@code DisposableBean} 上——而实测
 * <b>从未启动过的执行器一调 {@code destroy()} 就 NPE</b>（S5-3/S5-4：{@code triggerCallbackThreadHelper}
 * 为 null），并且 NPE 之前它已经把 handler 注册表清空了。本类把这件事变成一条看得见的守卫：
 * {@link #shutdown()} 只在 {@link #start()} 真的交出过之后才递给厂商。
 *
 * <p><b>不调 {@code setEnabled(false)}</b>：厂商那个开关只挡住 {@code start()} 的后半段，静态字段照设
 * （S5-1/S5-2），"用 enabled 当测试出路"会留下一台 JVM 里的静态污染。测试的出路是 {@link XxlJobRuntime}
 * 让位（Step 7 的 {@code AB7}）。
 */
public class DefaultXxlJobRuntime implements XxlJobRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultXxlJobRuntime.class);

    private final XxlJobExecutor executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    /**
     * 生产入口：四个值递给厂商 setter，其余厂商调参（{@code log-path}、{@code timeout}、
     * {@code log-retention-days}、{@code ip}、{@code address}）一律不重钉（§0.3-5），
     * 需要它们的人用 {@link #DefaultXxlJobRuntime(XxlJobExecutor)} 自己装配。
     */
    public DefaultXxlJobRuntime(String adminAddresses, String appName, String accessToken, int port) {
        this(configure(new XxlJobExecutor(), adminAddresses, appName, accessToken, port));
    }

    /** 逃生门，也是 {@code XS} 组的测试缝：把一个已经调好 setter 的执行器递进来。 */
    public DefaultXxlJobRuntime(XxlJobExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor 不能为 null");
    }

    /**
     * 把四个配置值递给厂商的四个 setter。<b>包私有</b>而不是 {@code private}：{@code XS8} 要能把它作用在
     * {@code CountingXxlJobExecutor} 上，逐字断言"只动这四个、其余厂商调参一个都不碰"（§0.3-5）——
     * 四参构造自己 {@code new} 的那个执行器从外面读不到，不开放这道门就只能靠反射，或者干脆不钉这条。
     * 开放范围停在同一个包内，测试类就在 {@code org.deep361.myddd.job.xxljob}，不新增对外 API。
     */
    static XxlJobExecutor configure(XxlJobExecutor executor, String adminAddresses, String appName,
                                    String accessToken, int port) {
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appName);
        executor.setAccessToken(accessToken);
        executor.setPort(port);
        return executor;
    }

    @Override
    public void bind(String jobName, IJobHandler handler) {
        if (stopped.get()) {
            throw new IllegalStateException("XXL-Job 执行器已停机，拒绝注册：" + jobName);
        }
        executor.registryJobHandler(jobName, handler);
    }

    @Override
    public IJobHandler load(String jobName) {
        return executor.loadJobHandler(jobName);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            log.info("XXL-Job 执行器已交出过启动，跳过重复启动：appname={}", executor.getAppname());
            return;
        }
        try {
            executor.start();
        } catch (Exception failure) {
            throw new FrameworkException("XXL-Job 执行器启动失败：appname=" + executor.getAppname()
                    + "，原因：" + failure + "（期望：调度中心地址可达、监听端口未被占用；"
                    + "键为 myddd.job.xxljob.admin-addresses / executor-port）");
        }
    }

    @Override
    public void shutdown() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        if (!started.get()) {
            log.info("XXL-Job 执行器从未启动，跳过销毁（厂商 destroy() 在未启动的实例上会 NPE，S5-3）");
            return;
        }
        executor.destroy();
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }
}
