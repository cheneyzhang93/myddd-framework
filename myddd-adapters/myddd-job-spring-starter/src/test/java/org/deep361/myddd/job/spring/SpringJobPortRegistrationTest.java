package org.deep361.myddd.job.spring;

import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SpringJobPort} 的注册面（{@code RS} 组）：全程跑在录制接缝上，一条都不 sleep。 */
class SpringJobPortRegistrationTest {

    private static final String VALID_CRON = "0 0 3 * * ?";

    private RecordingCronTaskScheduler scheduler;
    private SpringJobPort port;

    @BeforeEach
    void setUp() {
        scheduler = new RecordingCronTaskScheduler();
        port = new SpringJobPort(scheduler);
    }

    /** RS1：同名重复注册快速失败，消息把冲突两侧都说出来。 */
    @Test
    void should_fail_fast_when_the_same_job_name_is_registered_twice() {
        port.register(JobDefinition.of("nightly-settle", VALID_CRON), context -> {
        });
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> port.register(JobDefinition.of("nightly-settle", "0 0 5 * * ?"), context -> {
                }));
        assertThat(failure.getMessage())
                .contains("nightly-settle", VALID_CRON, "0 0 5 * * ?");
    }

    /** RS2：被拒的那次不改写已有注册——cron 与 handler 都还是第一次那一份。 */
    @Test
    void should_keep_the_original_registration_when_a_duplicate_is_rejected() {
        List<String> observed = new ArrayList<>();
        port.register(JobDefinition.of("nightly-settle", VALID_CRON), context -> observed.add("first"));
        assertThrows(IllegalArgumentException.class,
                () -> port.register(JobDefinition.of("nightly-settle", "0 0 5 * * ?"), context -> observed.add("second")));
        assertEquals(VALID_CRON, scheduler.cronOf("nightly-settle"));
        scheduler.fire("nightly-settle");
        assertEquals(List.of("first"), observed);
    }

    /** RS3：并发同抢一名，恰好一个成功（判重必须在同一步里完成，不能"先看后写"）。 */
    @Test
    void should_admit_exactly_one_of_two_concurrent_registrations_of_the_same_name() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> racers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    port.register(JobDefinition.of("contended", VALID_CRON), context -> {
                    });
                    accepted.incrementAndGet();
                } catch (IllegalArgumentException duplicate) {
                    rejected.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            thread.setDaemon(true);
            racers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : racers) {
            thread.join(5_000);
        }
        // 三件一起断：只断前两件在"两个线程都成功但接缝被调用两次"的实现上也会红，而那才是真正的覆盖丢失。
        assertEquals(1, accepted.get(), "两个线程都注册成功 = 判重没在同一步里完成");
        assertEquals(1, rejected.get());
        assertEquals(1, scheduler.scheduleOrder().size());
    }

    /** RS4：非法表达式在递给接缝之前就被拒，两处状态都干净。 */
    @Test
    void should_reject_an_illegal_cron_without_touching_any_state() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> port.register(JobDefinition.of("broken", "abc"), context -> {
                }));
        assertThat(failure.getMessage()).contains("broken", "abc");
        assertTrue(port.registeredJobs().isEmpty());
        assertFalse(scheduler.hasTask("broken"));
    }

    /** RS5：段数必须是 6（厂商的报错里没有任务名，我们的有）。 */
    @Test
    void should_reject_a_five_field_expression_with_the_job_name_in_the_message() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> port.register(JobDefinition.of("hourly", "0 0 * * *"), context -> {
                }));
        assertThat(failure.getMessage()).contains("hourly", "0 0 * * *", "6 段");
    }

    /** RS6：null 入参快速失败，并且不安排任何东西。 */
    @Test
    void should_reject_null_arguments_before_scheduling_anything() {
        assertThrows(NullPointerException.class, () -> port.register(null, context -> {
        }));
        assertThrows(NullPointerException.class,
                () -> port.register(JobDefinition.of("named", VALID_CRON), null));
        assertTrue(scheduler.scheduleOrder().isEmpty());
        assertTrue(port.registeredJobs().isEmpty());
    }

    /** RS7：合法表达式逐字透传，框架不替它 trim（厂商本来就容忍，多一层归一就是第二个真源）。 */
    @Test
    void should_hand_the_cron_to_the_scheduler_verbatim() {
        // 用规范构造而不是 of()：紧凑构造只挡"空白"，两侧带空格的串是合法值——这是"框架不归一"能被读出来的前提。
        port.register(new JobDefinition("spaced", "  " + VALID_CRON + " ", null), context -> {
        });
        assertEquals("  " + VALID_CRON + " ", scheduler.cronOf("spaced"));
        // 顺带证明端口没有把 null 任务参数在半路变成别的什么（JobContext 的规范化是唯一一处）。
        assertEquals("spaced", port.triggerNow("spaced", null).jobName());
    }

    /** RS8：接缝抛异常 ⇒ 注册表里不留半成品，失败后可以正常重注册。 */
    @Test
    void should_leave_no_trace_when_the_scheduler_refuses() {
        scheduler.failNextScheduleWith(new IllegalStateException("调度接缝拒绝"));
        assertThrows(IllegalStateException.class,
                () -> port.register(JobDefinition.of("rejected", VALID_CRON), context -> {
                }));
        assertFalse(port.registeredJobs().contains("rejected"));
        List<String> observed = new ArrayList<>();
        port.register(JobDefinition.of("rejected", "0 0 4 * * ?"), context -> observed.add("retry"));
        scheduler.fire("rejected");
        assertEquals(List.of("retry"), observed);
    }

    /** RS9：{@code registeredJobs()} 给的是不可变快照。 */
    @Test
    void should_hand_out_an_immutable_snapshot_of_the_registered_names() {
        port.register(JobDefinition.of("job-a", VALID_CRON), context -> {
        });
        Set<String> snapshot = port.registeredJobs();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("job-b"));
        port.register(JobDefinition.of("job-b", "0 0 4 * * ?"), context -> {
        });
        assertEquals(1, snapshot.size(), "先前取到的快照被后来的注册改写了：那不是快照");
        assertEquals(2, port.registeredJobs().size());
    }

    /** RS10：停机之后注册失败，而且失败形态是我们那句带任务名的话（不是厂商的 TaskRejectedException，它没有任务名）。 */
    @Test
    void should_refuse_registration_after_the_port_is_destroyed() {
        port.register(JobDefinition.of("nightly-settle", VALID_CRON), context -> {
        });
        port.destroy();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> port.register(JobDefinition.of("late", VALID_CRON), context -> {
                }));
        assertThat(failure.getMessage()).contains("late");
        assertFalse(scheduler.hasTask("late"));
    }

    /** RS11：停机幂等——接缝只被通知关一次。 */
    @Test
    void should_be_idempotent_when_destroying() {
        port.destroy();
        port.destroy();
        assertEquals(1, scheduler.shutdownCalls());
    }
}
