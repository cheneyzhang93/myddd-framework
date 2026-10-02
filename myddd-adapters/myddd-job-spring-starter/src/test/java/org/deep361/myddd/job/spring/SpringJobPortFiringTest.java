package org.deep361.myddd.job.spring;

import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DefaultCronTaskScheduler} 的真接线证明（{@code FL} 组）。这是本模块唯一允许等待的测试类。 */
class SpringJobPortFiringTest {

    /** 每秒触发一次：S1-P4 实测 3200ms 内 4 次，所以"至少 2 次"的预算给到 5 秒是宽松的一倍。 */
    private static final String EVERY_SECOND = "* * * * * ?";
    private static final long BUDGET_MILLIS = 5_000L;

    private SpringJobPort port;

    @AfterEach
    void tearDown() {
        if (port != null) {
            port.destroy();
        }
    }

    /** FL1：真的被 Spring 调度起来了（同时证明惰性 initialize() 那条路走通；不断准点性）。 */
    @Test
    void should_let_the_vendor_scheduler_fire_the_registered_handler() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        port = new SpringJobPort(new DefaultCronTaskScheduler(2));
        port.register(JobDefinition.of("tick", EVERY_SECOND), context -> fired.incrementAndGet());
        long deadline = System.currentTimeMillis() + BUDGET_MILLIS;
        while (fired.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(fired.get() >= 2, "5 秒内没有触发到第 2 次，实际：" + fired.get());
    }

    /** FL2：池子大小真起作用——两个长任务在 pool=2 上是并发而不是排队（S2-Q3/Q4 的差值）。 */
    @Test
    void should_run_two_long_jobs_concurrently_when_the_pool_allows_it() throws Exception {
        AtomicInteger started = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        port = new SpringJobPort(new DefaultCronTaskScheduler(2));
        for (String name : java.util.List.of("long-a", "long-b")) {
            port.register(JobDefinition.of(name, EVERY_SECOND), context -> {
                started.incrementAndGet();
                try {
                    Thread.sleep(2_500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                finished.incrementAndGet();
            });
        }
        long deadline = System.currentTimeMillis() + BUDGET_MILLIS;
        while (started.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(2, started.get(), "5 秒内两个任务都没开始跑，接线有问题");
        assertEquals(0, finished.get(),
                "两个 2500ms 的任务在刚起步时就有一个跑完了：池退化成串行（实测池 1 时这个值是 1）");
    }

    /** FL3：停机真的传到厂商——之后的周期不再进来。@AfterEach 会再 destroy() 一次，顺手把二次销毁幂等跑到真调度器上。 */
    @Test
    void should_stop_firing_after_the_port_is_destroyed() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        port = new SpringJobPort(new DefaultCronTaskScheduler(1));
        port.register(JobDefinition.of("tick", EVERY_SECOND), context -> fired.incrementAndGet());
        long deadline = System.currentTimeMillis() + BUDGET_MILLIS;
        while (fired.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(fired.get() >= 1, "停机前一次都没跑起来，这条用例的前提不成立");
        Thread.sleep(300);          // 让停机瞬间仍在跑的那一次落地——那不是"停机后又被触发"
        port.destroy();
        int afterShutdown = fired.get();
        Thread.sleep(2_500);        // 两个周期
        assertEquals(afterShutdown, fired.get(), "destroy() 之后仍有新的触发进来");
    }
}
