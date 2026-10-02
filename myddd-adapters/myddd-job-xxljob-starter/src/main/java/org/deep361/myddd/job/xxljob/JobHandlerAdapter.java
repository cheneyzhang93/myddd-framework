package org.deep361.myddd.job.xxljob;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.IJobHandler;
import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobHandler;

import java.util.Objects;

/**
 * 把内核的 {@link JobHandler} 包成厂商的 {@link IJobHandler}（R7：{@code @MydddJob} 是类级注解，
 * 而 {@code @XxlJob} 只能标方法，所以本计划走编程式注册、由这一层做形状转换）。
 *
 * <p><b>成功不写回执</b>（一手字节码读数，xxl-job-core 3.4.2）：{@code XxlJobContext} 的构造器把
 * {@code handleCode} 初始化成 {@code 200}（{@code sipush 200 → putfield handleCode}），而
 * {@code JobThread} 在 {@code handler.execute()} 正常返回后只在 {@code getHandleCode() <= 0} 时才补
 * {@code handleFail("job handle result lost.")}，并且每次执行都 {@code new XxlJobContext(...)}。
 * 所以"正常返回"在厂商侧本来就是成功；这里再写一句 {@code XxlJobHelper.handleSuccess()} 唯一的实际效果是
 * <b>把 handler 自己写过的回执覆盖成成功</b>。两条各由 {@code HD1}（一个字都不写）与 {@code HD8}
 * （handler 写的回执原样保留）钉住。
 *
 * <p><b>失败一律不写回执</b>：{@code delegate.execute(...)} 抛出的任何东西都原样透出去，让厂商的
 * {@code catch (Throwable)} 去落 {@code handleFail(栈轨迹)}。我们自己写一条只有 {@code getMessage()}
 * 的回执，等于把运维在 admin 日志里唯一能看到的信息（栈）覆盖掉；而"顺手 catch 住返回成功"更糟——
 * 那是把业务失败报成成功。两条方向分别由 {@code HD5}（{@code RuntimeException}）与 {@code HD6}
 * （{@code Error}）钉：它们证明的是"这里没有 catch"，不是"这里 catch 对了"。
 *
 * <p><b>不碰 ThreadLocal</b>：上下文（任务参数从哪来）由厂商 {@code JobThread} 在进入本方法之前设好，
 * 本层只读不写；{@code HD7} 断言 {@code execute()} 前后 {@code XxlJobContext.getXxlJobContext()}
 * 是同一个实例。少了这条钉子，下一个人"为了干净"在 finally 里清 ThreadLocal 就把厂商自己的上下文丢了。
 */
final class JobHandlerAdapter extends IJobHandler {

    private final String jobName;
    private final JobHandler delegate;

    JobHandlerAdapter(String jobName, JobHandler delegate) {
        this.jobName = Objects.requireNonNull(jobName, "jobName 不能为 null");
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为 null");
    }

    @Override
    public void execute() {
        // getJobParam() 在没有参数时返回 null（实测 S5-12/S5-15）；把它规范成空串是内核 JobContext
        // 紧凑构造的既有语义（JobContext.java:18），这里不再写第二遍。
        delegate.execute(new JobContext(jobName, XxlJobHelper.getJobParam()));
    }
}
