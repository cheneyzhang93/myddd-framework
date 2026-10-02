package org.deep361.myddd.job.spring;

import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.deep361.myddd.testing.contract.JobPortContractTestBase;

/** {@link SpringJobPort} 上的 {@code JOB-C1}–{@code JOB-C6}（六条继承而来，本类一个测试方法都不写）。 */
class SpringJobPortContractTest extends JobPortContractTestBase {

    /** 每个测试实例一个新端口（JUnit 5 默认 per-method 实例）——注册表绝不能是 static 的。 */
    private final SpringJobPort port = new SpringJobPort(new RecordingCronTaskScheduler());

    /** 录制接缝：六条断的是注册与派发，派发由 trigger 缝强制，一次都不需要真调度。 */
    @Override
    protected JobPort jobPort() {
        return port;
    }

    /** {@code trigger} 缝直接落在生产入口上：{@code triggerNow} 与真调度共用包装层，这条缝没有测试专用代码。 */
    @Override
    protected JobContext trigger(String jobName, String param) {
        return port.triggerNow(jobName, param);
    }

    /** 判重的失败类型 = 端口 javadoc 承诺的那一个（Step 0 第 3 条；Task 32 的假实现答错过，见 D-121）。 */
    @Override
    protected Class<? extends RuntimeException> duplicateRegistrationFailureType() {
        return IllegalArgumentException.class;
    }
}
