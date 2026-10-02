package org.deep361.myddd.job.spring;

import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.deep361.myddd.kernel.port.job.JobHandler;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link JobPort} 的 Spring 实现：本地 {@code ThreadPoolTaskScheduler} 调度，单机零外部依赖。
 *
 * <p><b>四件事在这一个类里做完</b>，每件都有对应用例编号（Step 7 的表）：
 * <ol>
 *   <li><b>判重</b>（{@code RS1}–{@code RS3}）：同名重复注册抛 {@link IllegalArgumentException}，消息含冲突
 *       任务名。这是 {@code JobPort} javadoc 的承诺，也是 R7 把判重责任交给适配器的落点——XXL-Job 的
 *       {@code registryJobHandler} 同名会静默覆盖，所以两个 provider 都必须自己做。</li>
 *   <li><b>注册期校验</b>（{@code RS4}–{@code RS7}）：cron 先用 {@link CronExpression#isValidExpression(String)}
 *       判过才递给调度器。厂商侧的报错是 {@code IllegalArgumentException: Cron expression must consist of 6
 *       fields}（S1-P2），里面没有任务名；框架的报错必须同时点出任务名与表达式。调度接缝失败时注册表里
 *       不留半成品（{@code RS8}），{@link #registeredJobs()} 给的是不可变快照（{@code RS9}）。</li>
 *   <li><b>失败记录</b>（{@code FA1}–{@code FA5}）：执行体一律包在 try/catch 里，失败既进日志也进可查询的
 *       {@link #lastFailureOf(String)}。<b>这是本类存在的核心理由</b>：实测一个每次抛异常的任务在 3.2 秒里被调度
 *       3 次、调度并不停（S2-Q2），但异常只换来 Spring 的一句 WARN，线程的
 *       {@code UncaughtExceptionHandler} 一次都没被调用（S2-Q2b）——不自己记，失败就只活在日志里，
 *       等价性基类与运维端点都无从读起。</li>
 *   <li><b>停机与拒绝</b>（{@code RS10}–{@code RS11}）：{@link #destroy()} 之后注册必须失败。厂商在
 *       {@code shutdown()} 之后 {@code schedule} 抛的是 {@code TaskRejectedException: ExecutorService in
 *       shutdown state did not accept task}（S1-P8），那句里没有任务名；端口先用自己的 closed 标志挡住，
 *       给出带任务名的 {@link IllegalStateException}。</li>
 * </ol>
 *
 * <p>{@link #triggerNow(String, String)} 是两件事共用的一个入口：{@code JobPortContractTestBase} 的
 * {@code trigger} 缝（JOB-C2/C3/C5/C6 需要"强制派发"）与运维的"现在跑一次"。它<b>不</b>等下一次 cron，
 * 而是在调用线程上直接执行包装后的 handler，因此同样走失败记录那条路（{@code FA4}）。
 *
 * <p>线程安全：注册表与失败表都是 {@code ConcurrentHashMap}，包装层无可变状态；停机标志由
 * {@link #lifecycleLock} 保护。同一任务被并发触发由厂商调度器决定，端口不加锁
 * （{@code JobHandler} 的 javadoc 已把"实现须自行保证线程安全"写给业务）。
 */
public class SpringJobPort implements JobPort, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SpringJobPort.class);

    private final Map<String, JobDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, JobExecutionFailure> failures = new ConcurrentHashMap<>();
    private final CronTaskScheduler scheduler;
    private final Object lifecycleLock = new Object();
    private boolean closed;

    /**
     * @param scheduler 调度接缝；生产装配传 {@link DefaultCronTaskScheduler}，
     *                  单测传录制用的假实现以便零等待断言（{@code RS}/{@code FA} 组共 16 条，一条都不 sleep）
     * @throws NullPointerException 入参为 {@code null}
     */
    public SpringJobPort(CronTaskScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler 不能为 null");
    }

    @Override
    public void register(JobDefinition definition, JobHandler handler) {
        Objects.requireNonNull(definition, "definition 不能为 null");
        Objects.requireNonNull(handler, "handler 不能为 null");
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("任务端口已关闭，拒绝注册：" + definition.name());
            }
        }
        String name = definition.name();
        if (!CronExpression.isValidExpression(definition.cron())) {
            throw new IllegalArgumentException("任务 " + name + " 的 cron 表达式不合法：" + definition.cron()
                    + "（期望 6 段 Quartz 风格，例如 0 0 3 * * ?）");
        }
        // 占位与判重必须是同一个原子操作：换成 containsKey+put 并发注册会让两条都"成功"（RS3），
        // 把旧值另去 definitions.get 读一遍则输的那条会读到 null、异常类型从 IAE 变成 NPE（D-127）。
        JobDefinition previous = definitions.putIfAbsent(name, definition);
        if (previous != null) {
            throw new IllegalArgumentException("任务名重复注册：" + name + "（已注册的表达式为 "
                    + previous.cron() + "，新提交的表达式为 " + definition.cron() + "）");
        }
        try {
            scheduler.schedule(name, () -> runGuarded(name, handler, JobContext.of(name)), definition.cron());
        } catch (RuntimeException schedulingFailure) {
            // 回滚占位：漏掉它，一次失败的注册就把这个名字永久占住（RS8）。
            definitions.remove(name);
            throw schedulingFailure;
        }
        // handlers.put 在 schedule 之后：register 抛出时 registeredJobs() 查不到该任务（RS8）。
        handlers.put(name, handler);
    }

    @Override
    public Set<String> registeredJobs() {
        return Set.copyOf(handlers.keySet());
    }

    /**
     * 在<b>调用线程</b>上立即执行一次已注册任务（契约基类的 {@code trigger} 缝 + 运维的"现在跑一次"）。
     *
     * @param jobName 任务名
     * @param param   任务参数，{@code null} 由 {@link JobContext} 规范化为空串
     * @return 本次执行的上下文（{@code JobPortContractTestBase} 的 JOB-C2/C3 读它的两个字段）
     * @throws IllegalArgumentException 任务名未注册（不许静默返回"什么都没发生"，JOB-C5）
     */
    public JobContext triggerNow(String jobName, String param) {
        JobHandler handler = handlers.get(jobName);
        if (handler == null) {
            throw new IllegalArgumentException("任务未注册：" + jobName
                    + "，已注册：" + registeredJobs());
        }
        JobContext context = new JobContext(jobName, param);
        runGuarded(jobName, handler, context);
        return context;
    }

    /** 该任务最近一次失败；从未失败过则为空（{@code FA2}–{@code FA5} 的唯一读点）。 */
    public Optional<JobExecutionFailure> lastFailureOf(String jobName) {
        return Optional.ofNullable(failures.get(jobName));
    }

    /** 全部失败记录，按任务名索引（Task 57 的死信/失败诊断端点用它，本任务只保证读得到）。 */
    public Map<String, JobExecutionFailure> failures() {
        return Map.copyOf(failures);
    }

    @Override
    public void destroy() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        scheduler.shutdown();
    }

    /**
     * 包装执行体：失败进日志、进 {@link #lastFailureOf}，并且<b>不外抛</b>。
     *
     * <p>不外抛的理由是一手读数：外抛时 Spring 的 {@code TaskUtils$LoggingErrorHandler} 只会打一句
     * WARN 加栈（S2-Q2b 的 {@code UncaughtExceptionHandler} 空列表就是这件事的证据），而调度照常继续
     * （S2-Q2＝3 次）——外抛换不来任何额外信息，只会让失败多一层没人负责的转手。
     * 记下来才是适配器的职责（{@code JobHandler} javadoc："适配器负责记录失败"）。
     *
     * <p>它同时是 {@code register}（真调度，参数为空串）与 {@code triggerNow}（手动派发，参数逐字透传）
     * 共用的<b>唯一</b>执行路径：JOB-C3 断的"参数逐字到达 handler"只有走同一条路才成立，
     * 两条路各写一份 try/catch 迟早分叉。
     *
     * <p>{@code catch (Throwable)} 而不是 {@code Exception}：{@code AssertionError} 也要被记录
     * （{@code FA3} 用 {@code Error} 级失败钉它），"让调度继续跑"优先于"让栈更好看"。
     */
    private void runGuarded(String jobName, JobHandler handler, JobContext context) {
        try {
            handler.execute(context);
        } catch (Throwable failure) {
            failures.put(jobName, new JobExecutionFailure(jobName, Instant.now(), failure));
            log.error("任务执行失败：job={}", jobName, failure);
        }
    }
}
