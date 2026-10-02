package org.deep361.myddd.job.xxljob;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.context.XxlJobHelper;
import org.deep361.myddd.kernel.port.job.JobContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link JobHandlerAdapter} 的映射与回执（{@code HD} 组）。上下文由用例自己设，一条都不起厂商线程。 */
class JobHandlerAdapterTest {

    private final List<JobContext> observed = new ArrayList<>();
    private XxlJobContext vendorContext;

    @BeforeEach
    void setUp() {
        observed.clear();
        vendorContext = new XxlJobContext(7L, "param-from-admin", 0L,
                System.currentTimeMillis(), "manual", 0, 1);
        XxlJobContext.setXxlJobContext(vendorContext);
    }

    @AfterEach
    void tearDown() {
        XxlJobContext.setXxlJobContext(null);
    }

    /**
     * HD1：正常返回时本层一个字都不写。
     *
     * <p>读数来自字节码而非文档：{@code XxlJobContext} 构造器把 {@code handleCode} 初始化成 {@code 200}，
     * 而 {@code JobThread} 只在 {@code getHandleCode() <= 0} 时才补 {@code handleFail("job handle result lost.")}，
     * 且每次执行都新建上下文。所以"回执是 200"是厂商的缺省，{@code handleMsg} 保持 {@code null} 才是
     * "本层没写过"的证据——第一次交付在这里写的是 {@code handleSuccess()}，那条断言当时是<b>假绿</b>：
     * 它测的是厂商的缺省值，不是我们的行为。
     */
    @Test
    void should_write_no_receipt_when_the_handler_returns() {
        new JobHandlerAdapter("nightly-settle", observed::add).execute();
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, vendorContext.getHandleCode(),
                "厂商缺省即 200（实测：新建上下文未经任何 helper 就读到 200）");
        assertNull(vendorContext.getHandleMsg(), "本层不该写回执文本");
        assertEquals(1, observed.size());
    }

    /** HD2：名字来自注册，不来自调度中心的整数 jobId。 */
    @Test
    void should_hand_the_registered_name_to_the_handler() {
        new JobHandlerAdapter("nightly-settle", observed::add).execute();
        assertEquals("nightly-settle", observed.get(0).jobName());
        assertEquals(7L, vendorContext.getJobId(), "厂商侧的 jobId 仍在，但它不是我们的任务名");
    }

    /** HD3：参数逐字来自厂商上下文。 */
    @Test
    void should_pass_the_admin_side_param_verbatim() {
        new JobHandlerAdapter("nightly-settle", observed::add).execute();
        assertEquals("param-from-admin", observed.get(0).param());
    }

    /** HD4：null 参数由内核 record 规范成空串——包装层不写第二遍。 */
    @Test
    void should_normalize_an_absent_param_through_the_kernel_record() {
        XxlJobContext.setXxlJobContext(new XxlJobContext(7L, null, 0L,
                System.currentTimeMillis(), "manual", 0, 1));
        new JobHandlerAdapter("nightly-settle", observed::add).execute();
        assertEquals("", observed.get(0).param(),
                "期望空串（内核 JobContext 紧凑构造的既有语义）；这里再写一层三元判断就是第二个真源");
    }

    /** HD5：业务失败原样透出，回执留给厂商写（我们写就只剩一句话，栈就没了）。 */
    @Test
    void should_propagate_a_business_failure_without_writing_a_receipt() {
        vendorContext.setHandleCode(XxlJobContext.HANDLE_CODE_FAIL);
        vendorContext.setHandleMsg("用例预置的回执");
        JobHandlerAdapter adapter = new JobHandlerAdapter("nightly-settle", context -> {
            throw new IllegalStateException("账不平");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class, adapter::execute);
        assertEquals("账不平", failure.getMessage());
        assertEquals(XxlJobContext.HANDLE_CODE_FAIL, vendorContext.getHandleCode(),
                "本层既没写成功回执也没替厂商写失败回执：厂商的 catch (Throwable) 才会落栈轨迹");
        assertEquals("用例预置的回执", vendorContext.getHandleMsg(), "回执文本仍是用例预置的那一句");
    }

    /** HD6：Error 级失败同样透出——这一条专门拒"catch (Throwable) 顺手兜住"的写法。 */
    @Test
    void should_propagate_an_error_as_well() {
        vendorContext.setHandleCode(XxlJobContext.HANDLE_CODE_FAIL);
        vendorContext.setHandleMsg("用例预置的回执");
        JobHandlerAdapter adapter = new JobHandlerAdapter("nightly-settle", context -> {
            throw new AssertionError("断言失败也要交给厂商");
        });
        assertThrows(AssertionError.class, adapter::execute);
        assertEquals(XxlJobContext.HANDLE_CODE_FAIL, vendorContext.getHandleCode());
        assertEquals("用例预置的回执", vendorContext.getHandleMsg());
    }

    /**
     * HD8：handler 自己写的回执原样保留。
     *
     * <p>这一格就是 {@code HD1} 那条裁定（不写 {@code handleSuccess()}）的行为面：内核 {@code JobHandler}
     * 没有回执概念，但 handler 完全可以直取 {@code XxlJobHelper} 写一条；我们在返回后再写一次成功，
     * 就会把那条失败盖成绿——admin 里最难查的一类事故。
     */
    @Test
    void should_leave_a_receipt_written_by_the_handler_alone() {
        JobHandlerAdapter adapter = new JobHandlerAdapter("nightly-settle", context -> {
            XxlJobHelper.handleFail("handler 自己判定的失败");
        });
        adapter.execute();
        assertEquals(XxlJobContext.HANDLE_CODE_FAIL, vendorContext.getHandleCode());
        assertEquals("handler 自己判定的失败", vendorContext.getHandleMsg());
    }

    /** HD7：包装层不碰 ThreadLocal。 */
    @Test
    void should_leave_the_vendor_context_untouched() {
        XxlJobContext before = XxlJobContext.getXxlJobContext();
        new JobHandlerAdapter("nightly-settle", observed::add).execute();
        assertSame(before, XxlJobContext.getXxlJobContext(),
                "在 finally 里'为了干净'清上下文，会把厂商自己的执行上下文一起清掉");
        assertTrue(observed.size() == 1);
    }
}
