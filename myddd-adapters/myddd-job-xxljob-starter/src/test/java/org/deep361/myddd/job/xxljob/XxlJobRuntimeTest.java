package org.deep361.myddd.job.xxljob;

import com.xxl.job.core.handler.IJobHandler;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.BindException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DefaultXxlJobRuntime} 对厂商执行器的调用面（{@code XS} 组）：计数替身，零真线程、零真端口。 */
class XxlJobRuntimeTest {

    private CountingXxlJobExecutor executor;
    private DefaultXxlJobRuntime runtime;

    @BeforeEach
    void setUp() {
        executor = new CountingXxlJobExecutor();
        runtime = new DefaultXxlJobRuntime(executor);
    }

    /** XS1：注册透传，参数逐字。 */
    @Test
    void should_pass_the_registration_through_to_the_vendor() {
        IJobHandler handler = new IJobHandler() {
            @Override
            public void execute() {
            }
        };
        runtime.bind("nightly-settle", handler);
        assertThat(executor.calls()).containsExactly("registry:nightly-settle");
    }

    /** XS2：读回透传，且读到的就是绑进去的那个实例。 */
    @Test
    void should_read_back_the_same_instance_from_the_vendor_table() {
        IJobHandler handler = new IJobHandler() {
            @Override
            public void execute() {
            }
        };
        runtime.bind("j1", handler);
        assertEquals(handler, runtime.load("j1"));
        assertThat(executor.calls()).containsExactly("registry:j1", "load:j1");
        assertNull(runtime.load("never-bound"));
    }

    /** XS3：没启动过就停机 ⇒ 守卫拦住厂商的 destroy（S5-3 的 NPE 就是这么避开的）。 */
    @Test
    void should_not_destroy_an_executor_that_was_never_started() {
        runtime.shutdown();
        assertThat(executor.calls()).doesNotContain("destroy");
        // 直接递给厂商会 NPE：这一行把"守卫确实起作用"变成可观察事实，而不是注释。
        assertThrows(NullPointerException.class, executor::destroy);
    }

    /** XS4：启动失败 ⇒ 换成能读懂的诊断，但已经交出去过的启动仍然要能被停机收尾。 */
    @Test
    void should_still_hand_the_destroy_to_the_vendor_when_start_failed() {
        executor.setAppname("order-executor");
        executor.failNextStartWith(new BindException("Address already in use"));
        FrameworkException failure = assertThrows(FrameworkException.class, runtime::start);
        assertThat(failure.getMessage()).contains("启动失败", "order-executor", "Address already in use",
                "myddd.job.xxljob");
        assertTrue(runtime.isStarted(), "started 回滚成 false 会让 shutdown 跳过 destroy，把已经起来的线程留在进程里");

        runtime.shutdown();
        assertThat(executor.calls()).containsExactly("start", "destroy");
    }

    /** XS5：重复启动幂等。 */
    @Test
    void should_start_the_vendor_executor_only_once() {
        runtime.start();
        runtime.start();
        assertEquals(1, executor.countOf("start"));
    }

    /** XS6：重复停机幂等，且停机不篡改"交出过启动"这个读数。 */
    @Test
    void should_destroy_the_vendor_executor_only_once() {
        runtime.start();
        runtime.shutdown();
        runtime.shutdown();
        assertEquals(1, executor.countOf("destroy"));
        assertTrue(runtime.isStarted(), "isStarted() 的语义是曾经把启动交给过厂商，不是现在还活着（Task 43 裁定）");
    }

    /** XS7：停机之后拒绝再注册，且不碰厂商表。 */
    @Test
    void should_refuse_to_bind_after_shutdown() {
        runtime.shutdown();
        runtime.start();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.bind("late-job", new IJobHandler() {
                    @Override
                    public void execute() {
                    }
                }));
        assertThat(failure.getMessage()).contains("已停机", "late-job");
        assertFalse(executor.calls().contains("registry:late-job"));
    }

    /** XS8：四个值递给四个 setter，其余一个都不碰。 */
    @Test
    void should_configure_exactly_the_four_documented_keys() {
        CountingXxlJobExecutor fresh = new CountingXxlJobExecutor();
        DefaultXxlJobRuntime.configure(fresh, "http://127.0.0.1:9099", "order-executor", "secret", 18000);
        assertThat(fresh.setters()).containsExactlyInAnyOrder(
                "adminAddresses=http://127.0.0.1:9099", "appname=order-executor",
                "accessToken=secret", "port=18000");
        assertEquals(18000, fresh.getPort());
        assertEquals("order-executor", fresh.getAppname());
        assertEquals("secret", fresh.getAccessToken());
    }
}
