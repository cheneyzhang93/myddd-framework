package org.deep361.myddd.job.xxljob;

import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.deep361.myddd.testing.contract.JobPortContractTestBase;

/** {@link XxlJobPort} 上的 {@code JOB-C1}–{@code JOB-C6}（六条继承而来，本类一个 {@code @Test} 都不写）。 */
class XxlJobPortContractTest extends JobPortContractTestBase {

    /** 每个测试实例一个新端口（JUnit 5 默认 per-method 实例）——注册表绝不能是 static 的。 */
    private final XxlJobPort port = new XxlJobPort(new RecordingXxlJobRuntime());

    @Override
    protected JobPort jobPort() {
        return port;
    }

    /** {@code trigger} 缝落在生产入口上：{@code triggerNow} 与厂商触发共用同一个包装层，这条缝没有测试专用代码。 */
    @Override
    protected JobContext trigger(String jobName, String param) {
        return port.triggerNow(jobName, param);
    }

    /** 判重的失败类型 = 端口 javadoc 承诺的那一个，与 spring 侧填的<b>同一个类</b>（见 D-121）。 */
    @Override
    protected Class<? extends RuntimeException> duplicateRegistrationFailureType() {
        return IllegalArgumentException.class;
    }
}
