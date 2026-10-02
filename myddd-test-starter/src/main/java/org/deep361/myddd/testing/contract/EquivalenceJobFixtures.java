package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobHandler;
import org.deep361.myddd.kernel.port.job.MydddJob;

import java.util.ArrayList;
import java.util.List;

/**
 * Job 等价性的<b>声明面夹具</b>：一份带 {@link MydddJob} 的 handler，两个 provider 各装配各的、
 * 读同一组常量。存在理由是"声明只写一次"——两侧各自抄一遍 name/cron，等价性就退化成"两次同一个笔误"。
 *
 * <p>注解三值与 {@code JOB_NAME}/{@code JOB_CRON}/{@code JOB_DESCRIPTION} 三个常量<b>逐字相同</b>，
 * 这条由 {@code EQ-T4} 用反射断言（改注解忘改常量时，两侧会读到同一个错值而仍然"等价"，只有反射能看见）。
 */
public final class EquivalenceJobFixtures {

    /** 夹具任务名。 */
    public static final String JOB_NAME = "eq-nightly-settle";
    /** Quartz 风格 6 段、spring 侧 {@code CronExpression.isValidExpression} 认（Task 41 取证 S1-P3a）。 */
    public static final String JOB_CRON = "0 0 3 * * ?";
    /** 说明文本：两侧都不解释它，只要求它出现在同一处（内核 {@code JobDefinition} 的第三个字段）。 */
    public static final String JOB_DESCRIPTION = "等价性夹具";

    private EquivalenceJobFixtures() {
    }

    /** 记录型 handler：{@code EQ6}/{@code EQ7} 读它收到的 {@link JobContext}，与 {@code dispatch} 的返回值对账。 */
    @MydddJob(name = JOB_NAME, cron = JOB_CRON, description = JOB_DESCRIPTION)
    public static final class RecordingJobHandler implements JobHandler {

        private final List<JobContext> received = new ArrayList<>();

        @Override
        public synchronized void execute(JobContext context) {
            received.add(context);
        }

        /** 快照：调用方拿到的是不可变副本，与本模块的其它用例同口径。 */
        public synchronized List<JobContext> received() {
            return List.copyOf(received);
        }
    }
}
