package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.deep361.myddd.kernel.port.job.JobHandler;
import org.deep361.myddd.kernel.port.job.JobPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link JobPortContractTestBase} 的进程内夹具（Task 32 Step 13 要求的配套交付物）。
 *
 * <p>注册表是实例字段而非 static：基类的前提是"每个测试实例一个新端口"，static 会让 JOB-C4 在 JOB-C1
 * 注册过的名字上先撞车，报错形态与"重复注册"完全不同、一眼可辨。
 */
class JobPortFakeContractTest extends JobPortContractTestBase {

    private final Map<String, JobHandler> handlers = new LinkedHashMap<>();

    private final JobPort port = new JobPort() {

        @Override
        public void register(JobDefinition definition, JobHandler handler) {
            // 判重责任在适配器（XXL-Job 自己会静默覆盖）；失败类型不是夹具自选的，
            // 而是 JobPort.register 的 javadoc 原文承诺（装配期错误用 IllegalArgumentException）
            if (handlers.containsKey(definition.name())) {
                throw new IllegalArgumentException("任务名重复注册：" + definition.name());
            }
            handlers.put(definition.name(), handler);
        }

        @Override
        public Set<String> registeredJobs() {
            return Set.copyOf(handlers.keySet());
        }
    };

    @Override
    protected JobPort jobPort() {
        return port;
    }

    @Override
    protected JobContext trigger(String jobName, String param) {
        JobHandler handler = handlers.get(jobName);
        if (handler == null) {
            throw new IllegalArgumentException("任务未注册：" + jobName);
        }
        JobContext context = new JobContext(jobName, param);
        handler.execute(context);
        return context;
    }

    @Override
    protected Class<? extends RuntimeException> duplicateRegistrationFailureType() {
        return IllegalArgumentException.class;
    }
}
