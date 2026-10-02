package org.deep361.myddd.job.spring;

import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SpringJobPort} 的失败记录（{@code FA} 组）。依据：S2-Q2"照常继续调度" + S2-Q2b"框架不留可读痕迹"。 */
class SpringJobPortFailureTest {

    private static final String VALID_CRON = "0 0 3 * * ?";

    private RecordingCronTaskScheduler scheduler;
    private SpringJobPort port;

    @BeforeEach
    void setUp() {
        scheduler = new RecordingCronTaskScheduler();
        port = new SpringJobPort(scheduler);
    }

    /** FA1：调度路径上的失败被记下来，并且不打扰调度器（异常不外抛）。 */
    @Test
    void should_record_a_failure_from_the_scheduled_path_without_rethrowing() {
        IllegalStateException business = new IllegalStateException("库存不足");
        port.register(JobDefinition.of("nightly-settle", VALID_CRON), context -> {
            throw business;
        });
        scheduler.fire("nightly-settle");
        JobExecutionFailure recorded = port.lastFailureOf("nightly-settle").orElseThrow();
        assertSame(business, recorded.cause());
        assertEquals("nightly-settle", recorded.jobName());
        assertNotNull(recorded.at());
    }

    /** FA2：没有失败就没有记录（读侧不许凭空造条目）。 */
    @Test
    void should_have_no_failure_record_for_a_job_that_never_failed() {
        List<String> observed = new ArrayList<>();
        port.register(JobDefinition.of("healthy", VALID_CRON), context -> observed.add(context.jobName()));
        scheduler.fire("healthy");
        assertTrue(port.lastFailureOf("healthy").isEmpty());
        assertTrue(port.failures().isEmpty());
        assertEquals(List.of("healthy"), observed);
    }

    /** FA3：{@code Error} 级失败也记——把 {@code catch (Throwable)} 写成 {@code catch (Exception)} 这条就红。 */
    @Test
    void should_record_an_error_level_failure_too() {
        AssertionError error = new AssertionError("断言式校验失败");
        port.register(JobDefinition.of("checked", VALID_CRON), context -> {
            throw error;
        });
        scheduler.fire("checked");
        assertSame(error, port.lastFailureOf("checked").orElseThrow().cause());
    }

    /** FA4：手动派发与真调度走同一个包装层，失败语义一致。 */
    @Test
    void should_record_a_failure_from_the_manual_trigger_path_the_same_way() {
        RuntimeException business = new RuntimeException("下游超时");
        port.register(JobDefinition.of("reconcile", "0 0 4 * * ?"), context -> {
            throw business;
        });
        JobContext context = port.triggerNow("reconcile", "2026-09-30");
        assertSame(business, port.lastFailureOf("reconcile").orElseThrow().cause());
        assertEquals("reconcile", context.jobName());
        assertEquals("2026-09-30", context.param());
    }

    /** FA5：失败按任务名分账，同一任务只留最近一次（"覆盖"靠同一 handler 两次抛出构造，同名重注册被 RS1 堵死）。 */
    @Test
    void should_keep_one_latest_failure_per_job() {
        RuntimeException earlier = new RuntimeException("第一次");
        RuntimeException later = new RuntimeException("第二次");
        AtomicInteger calls = new AtomicInteger();
        port.register(JobDefinition.of("job-a", VALID_CRON), context -> {
            throw calls.getAndIncrement() == 0 ? earlier : later;
        });
        port.register(JobDefinition.of("job-b", "0 0 4 * * ?"), context -> {
            throw later;
        });
        scheduler.fire("job-a");
        scheduler.fire("job-a");
        scheduler.fire("job-b");
        // 条数对（不多记）＋ 键对（没记到别人头上），两个方向一起断。
        assertEquals(2, port.failures().size());
        assertSame(later, port.lastFailureOf("job-a").orElseThrow().cause());
        assertSame(later, port.lastFailureOf("job-b").orElseThrow().cause());
        assertEquals(java.util.Set.of("job-a", "job-b"), port.failures().keySet());
    }
}
