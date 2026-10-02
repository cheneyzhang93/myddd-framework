package org.deep361.myddd.job.xxljob;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.handler.IJobHandler;
import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.deep361.myddd.kernel.port.job.JobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link XxlJobPort} 的注册面（{@code RS} 组）：全部跑在 {@link RecordingXxlJobRuntime} 上，零真线程。 */
class XxlJobPortRegistrationTest {

    private static final JobDefinition NIGHTLY = new JobDefinition("nightly-settle", "0 0 3 * * ?", "日终清算");

    private RecordingXxlJobRuntime runtime;
    private XxlJobPort port;
    private final List<JobContext> observed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        runtime = new RecordingXxlJobRuntime();
        port = new XxlJobPort(runtime);
        observed.clear();
    }

    /** RS1：同名重复注册 ⇒ 契约 javadoc 的失败类型，消息含任务名与两个 cron 串。 */
    @Test
    void should_reject_a_duplicate_job_name_and_show_both_crons() {
        port.register(NIGHTLY, context -> observed.add(context));
        IJobHandler boundBefore = runtime.boundHandler("nightly-settle");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> port.register(new JobDefinition("nightly-settle", "0 0 5 * * ?", "改过了"),
                        context -> observed.add(context)));
        assertThat(failure.getMessage()).contains("nightly-settle", "0 0 3 * * ?", "0 0 5 * * ?");
        assertEquals(boundBefore, runtime.boundHandler("nightly-settle"),
                "被拒的那次不许改动执行器上的绑定（厂商的 put 会覆盖，判重必须发生在 bind 之前）");
        port.triggerNow("nightly-settle", "x");
        assertEquals(1, observed.size());
        assertEquals("x", observed.get(0).param(), "派发到的应当是第一个 handler，参数逐字到达");
    }

    /** RS2：名字被外部（@XxlJob 方法或别的代码）占着 ⇒ 报"已被占用"，与"重复注册"是两条不同的诊断。 */
    @Test
    void should_refuse_to_overwrite_a_handler_we_do_not_own() {
        IJobHandler foreign = new IJobHandler() {
            @Override
            public void execute() {
                observed.add(new JobContext("foreign", ""));
            }
        };
        runtime.bindExternally("nightly-settle", foreign);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> port.register(NIGHTLY, context -> observed.add(context)));
        assertThat(failure.getMessage()).contains("nightly-settle", "已被占用");
        assertFalse(port.registeredJobs().contains("nightly-settle"),
                "被拒的注册不许留在注册表里（占位回滚）：" + port.registeredJobs());
        assertEquals(foreign, runtime.boundHandler("nightly-settle"),
                "我们没经营权就不该动它：执行器上仍是外部那个实例");

        IllegalArgumentException second = assertThrows(IllegalArgumentException.class,
                () -> port.register(NIGHTLY, context -> observed.add(context)));
        assertThat(second.getMessage())
                .as("回滚没做：一次被拒的注册把名字占在自己的表里，第二次就报成了\"重复注册\"，"
                        + "用户会去改那个根本没碰过的名字")
                .contains("已被占用");
    }

    /** RS3：并发同抢一名 ⇒ 恰好一个成功，bind 只发生一次。 */
    @Test
    void should_let_exactly_one_thread_win_a_concurrent_registration() throws Exception {
        JobHandler handler = context -> observed.add(context);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        Runnable race = () -> {
            ready.countDown();
            try {
                go.await();
                port.register(NIGHTLY, handler);
                succeeded.incrementAndGet();
            } catch (Throwable each) {
                synchronized (failures) {
                    failures.add(each);
                }
            }
        };
        Thread first = new Thread(race);
        Thread second = new Thread(race);
        first.start();
        second.start();
        ready.await();
        go.countDown();
        first.join(5000);
        second.join(5000);

        assertEquals(1, succeeded.get(), "两个线程都注册成功了，说明判重不是原子操作：" + failures);
        assertEquals(1, failures.size(), failures.toString());
        assertInstanceOf(IllegalArgumentException.class, failures.get(0));
        assertEquals(1, runtime.events().stream().filter(each -> each.equals("bind:nightly-settle")).count(),
                "接缝被安排了两次：" + runtime.events());
    }

    /** RS4：非法 cron 在本侧照单收下——这是 R7 承认的不对称，不许"为了等价"补校验。 */
    @Test
    void should_accept_an_expression_the_spring_side_would_reject() {
        port.register(new JobDefinition("loose", "abc", "本侧不校验"), context -> observed.add(context));
        assertTrue(port.registeredJobs().contains("loose"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> port.register(new JobDefinition("loose", "def", "还是不收口"), context -> {
                }));
        assertThat(failure.getMessage())
                .as("表里存的必须是注册原文；悄悄规范成缺省值就成了第二个真源")
                .contains("abc", "def");
    }

    /** RS5：执行器上绑的是包装层，不是业务 handler 本身。 */
    @Test
    void should_bind_a_vendor_shaped_wrapper_instead_of_the_business_handler() {
        port.register(NIGHTLY, context -> observed.add(context));
        assertInstanceOf(JobHandlerAdapter.class, runtime.boundHandler("nightly-settle"));
    }

    /** RS6：null 入参当场拒，且一条都不绑。 */
    @Test
    void should_reject_null_arguments_before_touching_the_executor() {
        assertThrows(NullPointerException.class, () -> port.register(null, context -> {
        }));
        assertThrows(NullPointerException.class, () -> port.register(NIGHTLY, null));
        assertTrue(runtime.events().isEmpty(), "入参校验失败却已经碰了接缝：" + runtime.events());
    }

    /** RS7：两个任务各自注册，互不影响。 */
    @Test
    void should_keep_two_jobs_independent() {
        port.register(NIGHTLY, context -> observed.add(context));
        port.register(new JobDefinition("reconcile", "0 0 4 * * ?", "对账"), context -> observed.add(context));
        assertEquals(2, runtime.events().stream().filter(each -> each.startsWith("bind:")).count());
        assertThat(port.registeredJobs()).contains("nightly-settle", "reconcile");
    }

    /** RS8：接缝失败 ⇒ 原样透出、注册表干净，且占位释放后能重注册（不留半成品）。 */
    @Test
    void should_release_the_claim_when_binding_fails() {
        runtime.failNextBindWith(new IllegalStateException("端口被占"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> port.register(NIGHTLY, context -> observed.add(context)));
        assertEquals("端口被占", failure.getMessage(), "接缝的失败必须原样透出，我们不换类型");
        assertFalse(port.registeredJobs().contains("nightly-settle"));

        port.register(NIGHTLY, context -> observed.add(context));
        assertTrue(port.registeredJobs().contains("nightly-settle"),
                "占位没释放：一次失败的绑定把这个名字永久占住了");
    }

    /** RS9：注册表读出来是快照。 */
    @Test
    void should_hand_out_an_immutable_snapshot_of_registered_jobs() {
        port.register(NIGHTLY, context -> observed.add(context));
        var snapshot = port.registeredJobs();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("intruder"));
        port.register(new JobDefinition("reconcile", "0 0 4 * * ?", "对账"), context -> observed.add(context));
        assertEquals(1, snapshot.size(), "先前拿到的集合跟着长了，说明给出去的是活视图");
    }

    /** RS10：停机之后注册 ⇒ IllegalStateException（守卫在接缝那一层，端口不另立标志）。 */
    @Test
    void should_refuse_registration_after_the_runtime_shut_down() {
        runtime.shutdown();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> port.register(NIGHTLY, context -> observed.add(context)));
        assertThat(failure.getMessage()).contains("已停机", "nightly-settle");
    }

    /** RS11：注册过、但执行器被销毁过 ⇒ 手动派发要说"读不到它"，不能说"跑成功了"。 */
    @Test
    void should_report_a_destroyed_executor_instead_of_silently_doing_nothing() {
        port.register(NIGHTLY, context -> observed.add(context));
        runtime.shutdown();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> port.triggerNow("nightly-settle", ""));
        assertThat(failure.getMessage()).contains("nightly-settle", "读不到");
        assertTrue(observed.isEmpty(), "抛了异常还把业务 handler 跑了：" + observed);
    }

    /** RS12：手动派发之后，厂商上下文必须还是它进来时的那一个（恢复 previous，不是清成 null、不是留下我们造的）。 */
    @Test
    void should_put_back_the_vendor_context_it_found_on_the_thread() {
        port.register(NIGHTLY, context -> observed.add(context));

        port.triggerNow("nightly-settle", "a");
        assertNull(XxlJobContext.getXxlJobContext(),
                "派发前是 null，派发后却留着我们造的那个上下文——真触发的线程会被它污染");

        XxlJobContext outer = new XxlJobContext(7L, "from-admin", 8L, System.currentTimeMillis(),
                "real.log", 0, 1);
        XxlJobContext.setXxlJobContext(outer);
        try {
            port.triggerNow("nightly-settle", "b");
            assertSame(outer, XxlJobContext.getXxlJobContext(),
                    "手动派发可能发生在一次真触发的处理线程里：把它清成 null 就是把厂商自己的上下文丢了（S5-18/S5-19）");
        } finally {
            XxlJobContext.setXxlJobContext(null);
        }
    }

    /** RS13：handler 抛 ⇒ 包成 IllegalStateException 并带上原因；异常那条路照样要走完上下文的恢复。 */
    @Test
    void should_wrap_a_handler_failure_when_triggering_manually() {
        port.register(new JobDefinition("crying", "0 0 6 * * ?", "会抛"), context -> {
            throw new IllegalStateException("boom");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> port.triggerNow("crying", ""));
        assertThat(failure.getMessage()).contains("crying", "boom");
        assertNull(XxlJobContext.getXxlJobContext(), "抛了异常这条路没走 finally，上下文留在我们造的那个上");
    }
}
