package org.deep361.myddd.job.xxljob;

import org.deep361.myddd.job.xxljob.config.JobProviderSelection;
import org.deep361.myddd.job.xxljob.config.MydddJobXxlJobProviderCondition;
import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.deep361.myddd.testing.contract.ConditionProbeRig;
import org.deep361.myddd.testing.contract.JobProviderEquivalenceTestBase;

import java.util.Set;

/**
 * {@link XxlJobPort} 上的 {@code EQ1}–{@code EQ8}（八条继承而来，本类一个测试注解都不写）。
 *
 * <p>与 {@code SpringJobProviderEquivalenceTest} 的<b>唯一</b>差别是六条缝各自指向本模块的生产代码：
 * 基类、期望表、夹具三样都在 {@code myddd-test-starter} 的 main 里，两侧共享。于是"两份 {@code JobProviderSelection}
 * 行为是否一致"这件事第一次有了可执行的判据（{@code 42f} 只能靠"改一侧必须改另一侧"这句纪律，那是人读的）。
 */
class XxlJobProviderEquivalenceTest extends JobProviderEquivalenceTestBase {

    /** 跑在 {@link RecordingXxlJobRuntime} 上：八条用例一条都不起厂商线程（与 {@code RS} 组同一前提）。 */
    private final XxlJobPort port = new XxlJobPort(new RecordingXxlJobRuntime());

    @Override
    protected String selfProviderKey() {
        return JobProviderSelection.XXLJOB;
    }

    @Override
    protected String selfPortClassName() {
        return XxlJobPort.class.getName();
    }

    /** 同 spring 侧的填法：驱动本模块那份生产条件类，取它问过的类名。 */
    @Override
    protected Set<String> probedMarkerNames() {
        return ConditionProbeRig.probe(new MydddJobXxlJobProviderCondition(),
                JobProviderSelection.KEY, null).probedClassNames();
    }

    @Override
    protected boolean shouldAssemble(String configured, Set<String> present) {
        return JobProviderSelection.shouldAssembleXxljob(configured, present);
    }

    @Override
    protected JobPort jobPort() {
        return port;
    }

    /** 派发缝落在生产入口 {@code triggerNow} 上：它会先写 {@code XxlJobContext} 再调绑定体，本类不绕开那半步。 */
    @Override
    protected JobContext dispatch(String jobName, String param) {
        return port.triggerNow(jobName, param);
    }
}
