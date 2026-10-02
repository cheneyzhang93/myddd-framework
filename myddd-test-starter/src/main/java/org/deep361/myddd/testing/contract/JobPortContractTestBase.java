package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JobPort} 的跨厂商契约：spring（本地调度）与 xxljob（远程调度）必须给出同一组可观察结果。
 *
 * <p>本基类钉的是**注册与派发**，不钉 cron 的生效时刻——那属于各自调度器，等价性无法在端口层面成立
 * （spring 用 {@code CronTrigger}，XXL-Job 的 cron 由 admin 侧解释，见 R7）。
 * "触发"由 {@link #trigger(String, String)} 这个缝提供：单测里直接派发已注册的 handler，
 * IT 里由 admin 触发后回读；两种实现都必须让 handler 收到同一个 {@link JobContext}。
 *
 * <p>{@link #jobPort()} 必须由子类保证"每个测试实例一个新端口"（JUnit 5 默认 per-method 实例，字段在实例上
 * 现建即可）；static 注册表会让 JOB-C4 因 JOB-C1 已注册同名而先失败，报错形态完全不同、一眼可辨。
 */
public abstract class JobPortContractTestBase {

    /** 被测端口。 */
    protected abstract JobPort jobPort();

    /** 强制派发一个已注册任务，回传 handler 实际收到的上下文。任务名不存在时必须抛 {@link IllegalArgumentException}。 */
    protected abstract JobContext trigger(String jobName, String param);

    /**
     * 该 provider 判定"同名重复注册"的失败类型（R7：判重责任在适配器，XXL-Job 自己会静默覆盖）。
     *
     * <p>这条缝开放的是<b>各 provider 的实现类型</b>，不是端口契约：合法取值只有
     * {@link IllegalArgumentException}——它是 {@code JobPort.register} 的 javadoc 原文承诺
     * （"同一任务名重复注册必须快速失败"），五个夹具与两个真 provider 都必须答这一个值。
     * 答成别的类型（例如 {@code BizException}）就是契约测试自己在另立真源。
     */
    protected abstract Class<? extends RuntimeException> duplicateRegistrationFailureType();

    /** JOB-C1：注册后任务名必须出现在 {@code registeredJobs()} 里。 */
    @Test
    void should_report_a_registered_job_as_known() {
        List<String> observed = new ArrayList<>();
        jobPort().register(JobDefinition.of("nightly-settle", "0 0 3 * * ?"),
                context -> observed.add(context.jobName()));
        assertTrue(jobPort().registeredJobs().contains("nightly-settle"),
                "注册成功但 registeredJobs 不含该任务：" + jobPort().registeredJobs());
    }

    /** JOB-C2：handler 收到的 jobName 必须与注册名逐字相同。 */
    @Test
    void should_hand_the_registered_name_to_the_handler() {
        jobPort().register(JobDefinition.of("nightly-settle", "0 0 3 * * ?"), context -> {
        });
        assertEquals("nightly-settle", trigger("nightly-settle", "").jobName());
    }

    /** JOB-C3：调度参数逐字透传（含"参数为空串"这一形态，不允许被成 null 或空格）。 */
    @Test
    void should_pass_the_trigger_param_verbatim() {
        jobPort().register(JobDefinition.of("reconcile", "0 0 4 * * ?"), context -> {
        });
        assertEquals("2026-09-30", trigger("reconcile", "2026-09-30").param());
        assertEquals("", trigger("reconcile", "").param());
    }

    /** JOB-C4：同名重复注册必须快速失败，消息含冲突的任务名（R7）。 */
    @Test
    void should_fail_fast_on_a_duplicate_job_name() {
        jobPort().register(JobDefinition.of("nightly-settle", "0 0 3 * * ?"), context -> {
        });
        RuntimeException failure = assertThrows(duplicateRegistrationFailureType(),
                () -> jobPort().register(JobDefinition.of("nightly-settle", "0 0 5 * * ?"), context -> {
                }));
        assertTrue(failure.getMessage().contains("nightly-settle"),
                "重复注册的失败消息必须点出冲突任务名，实际：" + failure.getMessage());
    }

    /** JOB-C5：未知任务名不许"静默什么都不做"，必须拒绝且不派发任何 handler。 */
    @Test
    void should_reject_triggering_an_unknown_job() {
        List<String> observed = new ArrayList<>();
        jobPort().register(JobDefinition.of("known", "0 0 3 * * ?"), context -> observed.add(context.jobName()));
        // 先把已知任务真触发一次：否则"observed 里没有未知任务"可以由"handler 根本从没被调过"来通过，
        // 那一格就成了同义反复，测不到"拒绝"这件事。
        trigger("known", "");
        assertThrows(IllegalArgumentException.class, () -> trigger("never-registered", ""));
        assertEquals(List.of("known"), observed);
    }

    /** JOB-C6：同一端口上的两个任务各归各的 handler，不串台。 */
    @Test
    void should_dispatch_each_job_to_its_own_handler() {
        jobPort().register(JobDefinition.of("job-a", "0 0 3 * * ?"), context -> {
        });
        jobPort().register(JobDefinition.of("job-b", "0 0 4 * * ?"), context -> {
        });
        assertEquals("job-a", trigger("job-a", "").jobName());
        assertEquals("job-b", trigger("job-b", "").jobName());
        assertTrue(jobPort().registeredJobs().containsAll(List.of("job-a", "job-b")));
    }
}
