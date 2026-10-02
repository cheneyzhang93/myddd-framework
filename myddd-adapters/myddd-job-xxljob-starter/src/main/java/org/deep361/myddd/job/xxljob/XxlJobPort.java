package org.deep361.myddd.job.xxljob;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.handler.IJobHandler;
import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.deep361.myddd.kernel.port.job.JobHandler;
import org.deep361.myddd.kernel.port.job.JobPort;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link JobPort} 的 XXL-Job 实现：把任务绑到执行器上，由调度中心决定何时触发。
 *
 * <p><b>cron 在本侧只声明、不生效</b>（R7）：执行器 jar 里没有任何 cron 入口（§0.1 实测：全解包搜
 * {@code cron} 零命中，{@code TriggerRequest} 只有 {@code jobId/executorHandler/executorParams/…}）。
 * {@link JobDefinition#cron()} 存在本端口自己的表里只服务两件事——判重诊断里的"已注册的 cron 为 …"，
 * 以及 Task 43 把同一个表达式写进 admin 的作业配置。因此本侧<b>不校验</b> cron 语法：那会把 R7
 * 明文承认的不对称抹平成假等价，非法表达式在这里照样注册成功，由调度中心在它那一侧报错。
 *
 * <p><b>判重有两处</b>（{@code RS1}/{@code RS2}）：先查自己的表（同一个名字 → 与 spring 侧同骨架的
 * "任务名重复注册"），再查执行器上是否已有同名（那条只能来自 {@code @XxlJob} 方法或外部代码 →
 * 诊断说"已被占用"）。顺序不能颠倒：厂商的 {@code registryJobHandler} 是 {@code put}，
 * 同名静默覆盖（实测 S5-5/S5-6/S5-7），先查执行器会让"同一个端口注册两次"报成"被别人占了"，
 * 用户按错的诊断去改错的配置。
 */
public class XxlJobPort implements JobPort {

    private final XxlJobRuntime runtime;
    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, JobDefinition> definitions = new ConcurrentHashMap<>();

    public XxlJobPort(XxlJobRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为 null");
    }

    @Override
    public void register(JobDefinition definition, JobHandler handler) {
        Objects.requireNonNull(definition, "definition 不能为 null");
        Objects.requireNonNull(handler, "handler 不能为 null");
        String name = definition.name();
        JobDefinition previous = definitions.putIfAbsent(name, definition);
        if (previous != null) {
            throw new IllegalArgumentException("任务名重复注册：" + name + "（已注册的 cron 为 "
                    + previous.cron() + "，新提交的 cron 为 " + definition.cron()
                    + "；XXL-Job 的同名注册会被执行器静默覆盖，判重由本端口负责）");
        }
        if (runtime.load(name) != null) {
            definitions.remove(name);
            throw new IllegalArgumentException("任务名 " + name + " 在 XXL-Job 执行器上已被占用"
                    + "（可能来自 @XxlJob 方法或外部直接调 registryJobHandler 的代码；本端口没有它的经营权，"
                    + "请改 @MydddJob 的 name 或去掉那个方法上的 @XxlJob）");
        }
        try {
            runtime.bind(name, new JobHandlerAdapter(name, handler));
        } catch (RuntimeException bindingFailure) {
            definitions.remove(name);
            throw bindingFailure;
        }
        handlers.put(name, handler);
    }

    @Override
    public Set<String> registeredJobs() {
        return Set.copyOf(handlers.keySet());
    }

    /**
     * 在<b>调用线程</b>上立即执行一次已注册任务（契约基类的 {@code trigger} 缝，也是运维的"现在跑一次"）。
     *
     * <p>它走的是 {@link XxlJobRuntime#load(String)} 拿到的<b>那个绑定实例</b>，不是 {@code handlers}
     * 表里的原始 {@link JobHandler}——于是"注册进去的东西"和"跑得起来的东西"必须是同一个对象这件事，
     * 在这条方法上被顺带验过（spring 侧的 {@code triggerNow} 同理）。
     *
     * <p>上下文由本方法自己造、自己恢复：{@code previous} 存的是调用前的 {@code XxlJobContext}，
     * {@code finally} 里<b>恢复它而不是清成 null</b>（实测 S5-18/S5-19）——手动派发可能发生在一次
     * 真触发的处理线程里，清空就把厂商自己的上下文丢了。
     *
     * @param jobName 任务名
     * @param param   任务参数，{@code null} 由 {@link JobContext} 规范成空串
     * @return handler 实际收到的上下文
     * @throws IllegalArgumentException 任务名未注册
     * @throws IllegalStateException 端口登记过但执行器上读不到（执行器被销毁过），或 handler 执行抛异常
     */
    public JobContext triggerNow(String jobName, String param) {
        if (!handlers.containsKey(jobName)) {
            throw new IllegalArgumentException("任务未注册：" + jobName + "，已注册：" + registeredJobs());
        }
        IJobHandler bound = runtime.load(jobName);
        if (bound == null) {
            throw new IllegalStateException("任务 " + jobName + " 在本端口登记过，但 XXL-Job 执行器上读不到它"
                    + "（执行器被销毁过？厂商的 destroy() 会清空 handler 注册表，需要重启应用重新注册）");
        }
        XxlJobContext previous = XxlJobContext.getXxlJobContext();
        XxlJobContext.setXxlJobContext(
                new XxlJobContext(0L, param, 0L, System.currentTimeMillis(), "manual", 0, 1));
        try {
            bound.execute();
        } catch (Exception failure) {
            throw new IllegalStateException("手动派发失败：" + jobName + "，原因：" + failure, failure);
        } finally {
            XxlJobContext.setXxlJobContext(previous);
        }
        return new JobContext(jobName, param);
    }
}
