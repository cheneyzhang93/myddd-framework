package org.deep361.myddd.job.spring;

import org.deep361.myddd.job.spring.config.JobProviderSelection;
import org.deep361.myddd.job.spring.config.MydddJobProviderCondition;
import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.deep361.myddd.testing.contract.ConditionProbeRig;
import org.deep361.myddd.testing.contract.JobProviderEquivalenceTestBase;

import java.util.Set;

/**
 * {@link SpringJobPort} 上的 {@code EQ1}–{@code EQ8}（八条继承而来，本类一个测试注解都不写）。
 *
 * <p>六条缝的填法本身就是本 provider 的<b>自述</b>：每一条都指向生产代码，没有一条指向测试里临时搭的东西。
 * 这也是本类存在的意义——等价性的判据全在期望表里，本类只负责"把本侧真实的那个答复交出来"。
 */
class SpringJobProviderEquivalenceTest extends JobProviderEquivalenceTestBase {

    /** 每个测试实例一个新端口（八条继承用例共用同一条前提：注册表不许是 static 的）。 */
    private final SpringJobPort port = new SpringJobPort(new RecordingCronTaskScheduler());

    @Override
    protected String selfProviderKey() {
        return JobProviderSelection.SPRING;
    }

    @Override
    protected String selfPortClassName() {
        return SpringJobPort.class.getName();
    }

    /**
     * 让<b>生产条件类</b>在"什么都不在 classpath 上"的假类加载器上真跑一次判定，把它问过的类名交出来。
     * {@code configuredValue} 传 {@code null}（相当于"这一键没写"）：这一支不会走歧义、不抛，判定必然走到
     * {@code presentProviders(...)}，于是 {@code MARKERS} 的两个名字都会被问到。
     */
    @Override
    protected Set<String> probedMarkerNames() {
        return ConditionProbeRig.probe(new MydddJobProviderCondition(),
                JobProviderSelection.KEY, null).probedClassNames();
    }

    @Override
    protected boolean shouldAssemble(String configured, Set<String> present) {
        return JobProviderSelection.shouldAssembleSpring(configured, present);
    }

    @Override
    protected JobPort jobPort() {
        return port;
    }

    /** 派发缝落在生产入口 {@code triggerNow} 上：与真调度共用包装层，这条缝没有测试专用代码。 */
    @Override
    protected JobContext dispatch(String jobName, String param) {
        return port.triggerNow(jobName, param);
    }
}
