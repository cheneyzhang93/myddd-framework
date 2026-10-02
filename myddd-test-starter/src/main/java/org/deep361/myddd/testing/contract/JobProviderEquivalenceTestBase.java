package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.deep361.myddd.kernel.port.job.JobHandler;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * spring 与 xxljob 两个 Job provider 的<b>等价性</b>基类：八条用例两侧各跑一遍，每一格期望都来自
 * {@link JobEquivalenceExpectations}——本类一个字都不自己判断"应该是什么"。
 *
 * <p>与 {@link JobPortContractTestBase} 的分工写在这里，因为最容易被"顺手合并两个基类"破坏：那条基类钉
 * <b>端口契约</b>（注册结果、判重的异常类型、派发到的上下文、未注册必须抛），本类钉 <b>装配决策与声明面</b>
 * （选谁、探测对方用的名字、同一份 {@code @MydddJob} 声明在两侧的注册与派发、cron 的不对称）。
 * 输入面不同、判据不同，合并只会让两侧的 {@code PS}/{@code RS} 组失去对照物。
 *
 * <p>只用 JUnit 原生断言：{@code myddd-test-starter} 的 main 作用域里没有 AssertJ（一手核对
 * {@code myddd-test-starter/pom.xml}），引它就是给所有消费模块加一条传递依赖。
 */
public abstract class JobProviderEquivalenceTestBase {

    /** 本侧的 provider 标识，必须是 {@link JobEquivalenceExpectations#MARKERS} 的一个 key。 */
    protected abstract String selfProviderKey();

    /** 本侧 port 实现类的真实全限定名（子类写 {@code SpringJobPort.class.getName()} 这一类表达式）。 */
    protected abstract String selfPortClassName();

    /**
     * 本侧<b>生产</b>条件类在判定过程中问过的全部类名，由子类用
     * {@link ConditionProbeRig#probe(org.springframework.context.annotation.Condition, String, String)}
     * 驱动一次真实判定采集而来（假的 classloader 一律回答"不在"，于是条件类必须把 {@code MARKERS} 里的
     * 两个名字都问一遍）。
     *
     * <p>这条缝取的是<b>行为</b>而不是字段：探测字符串住在生产类的私有常量里，测试里抄一份等于把钉子钉在
     * 副本上——一侧改名时副本跟着改，断言照绿。问过什么名字，才是那份代码真实的使用面。
     */
    protected abstract Set<String> probedMarkerNames();

    /** 本侧那份 {@code JobProviderSelection} 的答复。 */
    protected abstract boolean shouldAssemble(String configured, Set<String> present);

    /** 被测端口：每个测试实例一个新端口（{@link JobPortContractTestBase} 的同一条前提，注册表不许是 static 的）。 */
    protected abstract JobPort jobPort();

    /** 强制派发一次并返回本次上下文：两侧都落在各自的生产入口 {@code triggerNow} 上，本类没有测试专用路径。 */
    protected abstract JobContext dispatch(String jobName, String param);

    /** 对方那一格的 key 由本侧推出，不做成第七条缝——缝越少，两侧能各自填错的地方越少。 */
    private String otherProviderKey() {
        return JobEquivalenceExpectations.SPRING.equals(selfProviderKey())
                ? JobEquivalenceExpectations.XXLJOB : JobEquivalenceExpectations.SPRING;
    }

    private static JobDefinition definition(String name, String cron) {
        return new JobDefinition(name, cron, "");
    }

    /** EQ1：本侧可达且非歧义的每一行，答复逐格等于期望表里本侧那一格。 */
    @Test
    void should_answer_the_selection_table_for_every_reachable_row() {
        for (JobEquivalenceExpectations.Selection row : JobEquivalenceExpectations.SELECTIONS) {
            Boolean expected = row.expectedFor(selfProviderKey());
            if (expected == null) {
                continue;
            }
            assertEquals(expected, shouldAssemble(row.configured(), row.present()),
                    "答复与期望表不符：configured=" + row.configured() + "，present=" + row.present());
        }
    }

    /** EQ2：歧义输入必须抛，而且两侧的诊断骨架同形（括号里的措辞不比，见期望表的 {@code DUPLICATE_MESSAGE_PREFIX} 同一纪律）。 */
    @Test
    void should_refuse_to_choose_for_me_when_the_input_is_ambiguous() {
        for (JobEquivalenceExpectations.Selection row : JobEquivalenceExpectations.SELECTIONS) {
            if (!row.ambiguous()) {
                continue;
            }
            FrameworkException failure = assertThrows(FrameworkException.class,
                    () -> shouldAssemble(row.configured(), row.present()),
                    "歧义输入没抛：configured=" + row.configured() + "，present=" + row.present());
            String message = String.valueOf(failure.getMessage());
            assertTrue(message.contains(JobEquivalenceExpectations.SELECTION_KEY)
                            && message.contains(JobEquivalenceExpectations.SPRING)
                            && message.contains(JobEquivalenceExpectations.XXLJOB)
                            && message.contains("§0.3-6"),
                    "歧义诊断与另一侧不同形（四要素缺一不可）：" + message);
        }
    }

    /** EQ3：改名/挪包这件事在两侧各自的用户手册里都是静默的，只有交叉断言能看见。 */
    @Test
    void should_probe_the_other_provider_by_the_documented_marker() {
        assertEquals(JobEquivalenceExpectations.MARKERS.get(selfProviderKey()), selfPortClassName(),
                "本侧实现类的真实名字与期望表不符：一侧改名而另一侧的探测没跟");
        assertEquals(Set.copyOf(JobEquivalenceExpectations.MARKERS.values()), probedMarkerNames(),
                "本侧生产条件类问过的类名与期望表不符：表在改一个没人用的副本");
    }

    /** EQ4：同一份声明在两侧的注册结果相同（名字集合逐字相同，多一个少一个都算漂）。 */
    @Test
    void should_register_the_shared_declaration_and_report_the_same_names() {
        jobPort().register(new JobDefinition(JobEquivalenceExpectations.DECLARED_JOB_NAME,
                        JobEquivalenceExpectations.DECLARED_CRON,
                        JobEquivalenceExpectations.DECLARED_DESCRIPTION),
                new EquivalenceJobFixtures.RecordingJobHandler());
        assertEquals(Set.of(JobEquivalenceExpectations.DECLARED_JOB_NAME), jobPort().registeredJobs());
    }

    /** EQ5：判重的类型与消息骨架两侧同形（前缀 + 任务名 + <b>两个</b> cron 串都在）。 */
    @Test
    void should_reject_a_second_registration_with_the_shared_skeleton() {
        JobPort port = jobPort();
        port.register(definition(JobEquivalenceExpectations.DECLARED_JOB_NAME,
                JobEquivalenceExpectations.DECLARED_CRON), new EquivalenceJobFixtures.RecordingJobHandler());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> port.register(definition(JobEquivalenceExpectations.DECLARED_JOB_NAME,
                        JobEquivalenceExpectations.SECOND_CRON), new EquivalenceJobFixtures.RecordingJobHandler()));
        String message = String.valueOf(failure.getMessage());
        assertTrue(message.startsWith(JobEquivalenceExpectations.DUPLICATE_MESSAGE_PREFIX),
                "判重消息的前缀与另一侧不同：" + message);
        assertTrue(message.contains(JobEquivalenceExpectations.DECLARED_JOB_NAME)
                        && message.contains(JobEquivalenceExpectations.DECLARED_CRON)
                        && message.contains(JobEquivalenceExpectations.SECOND_CRON),
                "判重诊断里三样缺一样（名字、旧 cron、新 cron）：" + message);
    }

    /** EQ6：派发出去的上下文与 handler 收到的必须是同一个东西，参数一个空格都不许多。 */
    @Test
    void should_hand_the_same_context_to_the_handler_and_the_caller() {
        EquivalenceJobFixtures.RecordingJobHandler handler = new EquivalenceJobFixtures.RecordingJobHandler();
        jobPort().register(new JobDefinition(JobEquivalenceExpectations.DECLARED_JOB_NAME,
                JobEquivalenceExpectations.DECLARED_CRON, JobEquivalenceExpectations.DECLARED_DESCRIPTION), handler);
        int expectedCount = 0;
        for (String param : JobEquivalenceExpectations.DISPATCH_PARAMS) {
            expectedCount++;
            JobContext reported = dispatch(JobEquivalenceExpectations.DECLARED_JOB_NAME, param);
            List<JobContext> received = handler.received();
            assertEquals(expectedCount, received.size(), "第 " + expectedCount + " 次派发没进到 handler");
            JobContext handed = received.get(expectedCount - 1);
            assertEquals(JobEquivalenceExpectations.DECLARED_JOB_NAME, handed.jobName(),
                    "handler 收到的任务名与注册名不同（xxljob 侧最容易漂成 jobId 的那一件事）");
            assertEquals(param, handed.param(), "参数被改了（trim 或规范化都不许）：" + param);
            assertEquals(reported, handed, "dispatch 的返回值与 handler 收到的不是同一个形状");
        }
    }

    /** EQ7：{@code dispatch(name, null)} 两侧都把参数规范成空串（内核 {@code JobContext} 的规矩，不许任何一侧再包一层）。 */
    @Test
    void should_normalize_a_null_param_the_same_way_on_both_sides() {
        EquivalenceJobFixtures.RecordingJobHandler handler = new EquivalenceJobFixtures.RecordingJobHandler();
        jobPort().register(new JobDefinition(JobEquivalenceExpectations.DECLARED_JOB_NAME,
                JobEquivalenceExpectations.DECLARED_CRON, JobEquivalenceExpectations.DECLARED_DESCRIPTION), handler);
        JobContext reported = dispatch(JobEquivalenceExpectations.DECLARED_JOB_NAME, null);
        assertEquals(JobEquivalenceExpectations.NULL_PARAM_EXPECTED, reported.param(),
                "dispatch 返回值里 null 没被规范");
        assertEquals(JobEquivalenceExpectations.NULL_PARAM_EXPECTED, handler.received().get(0).param(),
                "handler 收到的 null 没被规范");
    }

    /** EQ8：非法 cron 在本侧被拒还是被收，等于期望表本侧那一格——不对称要被钉住，不是被抹平。 */
    @Test
    void should_keep_the_documented_cron_asymmetry_for_this_side() {
        JobPort port = jobPort();
        IllegalArgumentException rejected = null;
        try {
            port.register(definition(JobEquivalenceExpectations.INVALID_CRON_JOB_NAME,
                    JobEquivalenceExpectations.INVALID_CRON), new EquivalenceJobFixtures.RecordingJobHandler());
        } catch (IllegalArgumentException expected) {
            rejected = expected;
        }
        boolean validates = Boolean.TRUE.equals(
                JobEquivalenceExpectations.VALIDATES_CRON_AT_REGISTRATION.get(selfProviderKey()));
        assertEquals(validates, rejected != null, "本侧对非法 cron 的答复与期望表不符");
        if (rejected != null) {
            String message = String.valueOf(rejected.getMessage());
            assertTrue(message.contains(JobEquivalenceExpectations.INVALID_CRON_JOB_NAME)
                    && message.contains(JobEquivalenceExpectations.INVALID_CRON),
                    "非法 cron 的诊断里少了任务名或表达式原文：" + message);
            assertEquals(Set.of(), port.registeredJobs(), "被拒的注册在表里留下残渣");
        } else {
            assertEquals(Set.of(JobEquivalenceExpectations.INVALID_CRON_JOB_NAME), port.registeredJobs(),
                    "收了却不在表里：cron 由调度中心解释，但任务名必须看得见");
        }
    }
}
