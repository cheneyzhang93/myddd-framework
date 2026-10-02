package org.deep361.myddd.job.xxljob.config;

import com.xxl.job.core.executor.XxlJobExecutor;
import org.deep361.myddd.job.xxljob.MydddXxlJobScanner;
import org.deep361.myddd.job.xxljob.RecordingXxlJobRuntime;
import org.deep361.myddd.job.xxljob.XxlJobPort;
import org.deep361.myddd.job.xxljob.XxlJobRuntime;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.deep361.myddd.kernel.port.job.JobHandler;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.deep361.myddd.kernel.port.job.MydddJob;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动装配面（{@code AB} 组）：{@code ApplicationContextRunner} 为主，两条用真容器。 */
class MydddJobXxlJobAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddJobXxlJobAutoConfiguration.class))
            .withPropertyValues("myddd.job.xxljob.admin-addresses=http://127.0.0.1:9099");

    /** AB1：三个 bean 齐，厂商执行器不外露。 */
    @Test
    void should_assemble_runtime_port_and_scanner_without_publishing_the_vendor_executor() {
        runner.run(context -> {
            assertEquals(1, context.getBeanNamesForType(XxlJobRuntime.class).length);
            assertEquals(1, context.getBeanNamesForType(XxlJobPort.class).length);
            assertEquals(1, context.getBeanNamesForType(MydddXxlJobScanner.class).length);
            assertEquals(0, context.getBeanNamesForType(XxlJobExecutor.class).length,
                    "执行器不许作为 bean 暴露：一台 JVM 只允许一个已启动的执行器（S5-2/S5-21）");
        });
    }

    /** AB2：总开关关掉时一个 bean 都不产。 */
    @Test
    void should_produce_nothing_when_the_capability_is_disabled() {
        runner.withPropertyValues("myddd.job.enabled=false").run(context -> {
            assertTrue(context.getBeansOfType(XxlJobPort.class).isEmpty());
            assertTrue(context.getBeansOfType(MydddXxlJobScanner.class).isEmpty());
            assertTrue(context.getBeansOfType(MydddJobXxlJobProperties.class).isEmpty(),
                    "enabled=false 由条件类挡住，连属性 bean 都不该被注册");
        });
    }

    /** AB3：显式选中——选中自己装配，选中别人让位。 */
    @Test
    void should_assemble_only_for_the_provider_actually_selected() {
        runner.withPropertyValues("myddd.job.provider=xxljob")
                .run(context -> assertEquals(1, context.getBeanNamesForType(XxlJobPort.class).length));
        runner.withPropertyValues("myddd.job.provider=spring")
                .run(context -> assertTrue(context.getBeansOfType(XxlJobPort.class).isEmpty(),
                        "显式选了 spring，xxljob 侧必须一个 bean 都不产"));
    }

    /** AB4：端口不合期望时，真绑定路径也要快速失败并说得出键名。 */
    @Test
    void should_fail_startup_with_the_offending_key_when_the_port_is_illegal() {
        runner.withPropertyValues("myddd.job.xxljob.executor-port=80").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.job.xxljob.executor-port");
        });
    }

    /** AB5：两个 bean 抢同一任务名 ⇒ 启动失败；占住名字的那一个由 bean 名排序决定。 */
    @Test
    void should_report_a_duplicated_job_name_from_two_beans_deterministically() {
        runner.withBean("b-dup", SecondDup.class).withBean("a-dup", FirstDup.class).run(context -> {
            assertThat(context).hasFailed();
            // 扫描发生在 afterSingletonsInstantiated，Spring 不再包一层 BeanCreationException：断的是启动失败
            // 本身，写成 rootCause() 会因为它没有 cause 而直接红（实测 AB5 第一次就是这么红的）。
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dup")
                    .hasMessageContaining("已注册的 cron 为 0 0 8 * * ?");
        });
    }

    /** AB6：用户自带端口时端口让位，扫描器跟着注到用户那个端口上。 */
    @Test
    void should_step_aside_for_a_user_supplied_job_port_but_keep_scanning() {
        runner.withUserConfiguration(UserPortConfiguration.class)
                .withBean(SettleJob.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertTrue(context.getBeansOfType(XxlJobPort.class).isEmpty(),
                            "@ConditionalOnMissingBean(JobPort.class) 失效：用户自己写的端口被我们的实现顶掉了");
                    assertTrue(context.getBean(UserPort.class).registeredJobs().contains("nightly-settle"),
                            "端口让位之后扫描器必须跟着注到用户那个端口上");
                });
    }

    /** AB7：用户自带 runtime 时我们的 runtime 让位，我们的校验也不跑。 */
    @Test
    void should_step_aside_for_a_user_supplied_runtime_and_skip_our_validation() {
        runner.withPropertyValues("myddd.job.xxljob.admin-addresses=")
                .withBean(XxlJobRuntime.class, RecordingXxlJobRuntime::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertInstanceOf(RecordingXxlJobRuntime.class, context.getBean(XxlJobRuntime.class),
                            "用户贡献的 runtime 必须被扫描器用上");
                    assertEquals(1, context.getBeanNamesForType(XxlJobPort.class).length,
                            "端口仍在（用户只换了 runtime，没换 JobPort）");
                });
    }

    /** AB8：三个 bean 的名字是排障出口，必须逐字稳定。 */
    @Test
    void should_publish_the_three_beans_under_documented_names() {
        runner.run(context -> {
            assertInstanceOf(XxlJobRuntime.class, context.getBean("mydddXxlJobRuntime"));
            assertInstanceOf(XxlJobPort.class, context.getBean("mydddJobPort"));
            assertInstanceOf(MydddXxlJobScanner.class, context.getBean("mydddJobScanner"));
        });
    }

    /** AB9：真容器里带注解的 bean 被注册，且启动排在所有注册之后。 */
    @Test
    void should_bind_every_job_before_starting_the_executor() {
        RecordingXxlJobRuntime runtime = new RecordingXxlJobRuntime();
        runner.withBean(XxlJobRuntime.class, () -> runtime).withBean(SettleJob.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertEquals(List.of("bind:nightly-settle", "start"), runtime.events(),
                    "启动早于绑定 ⇒ 调度中心在这一窗口下发的触发找不到 handler");
        });
    }

    /** AB10：关闭一台零任务的容器不许抛——NPE 守卫在容器路径上的对应面。 */
    @Test
    void should_close_a_real_context_that_never_started_the_executor() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext context =
                     new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources()
                    .addFirst(new org.springframework.core.env.MapPropertySource("test",
                            java.util.Map.of("myddd.job.xxljob.admin-addresses", "http://127.0.0.1:9099")));
            context.register(MydddJobXxlJobAutoConfiguration.class);
            context.refresh();
            XxlJobRuntime runtime = context.getBean(XxlJobRuntime.class);
            assertFalse(runtime.isStarted(), "零任务却启动了执行器：AB1 与 SG6 定的'没任务不出网'在这里破了");
            context.close();
            assertFalse(runtime.isStarted());
        }
    }

    /** 装配面共用的三个夹具：一个正常 job、两个抢同一名字、一个用户自带的端口。 */
    @MydddJob(name = "nightly-settle", cron = "0 0 3 * * ?")
    public static class SettleJob implements JobHandler {
        @Override
        public void execute(JobContext context) {
        }
    }

    @MydddJob(name = "dup", cron = "0 0 8 * * ?")
    public static class FirstDup implements JobHandler {
        @Override
        public void execute(JobContext context) {
        }
    }

    @MydddJob(name = "dup", cron = "0 0 9 * * ?")
    public static class SecondDup implements JobHandler {
        @Override
        public void execute(JobContext context) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserPortConfiguration {

        @Bean
        UserPort userPort() {
            return new UserPort();
        }
    }

    /** 用户自己接公司内部调度平台时的端口形态：只实现接口，不碰我们的实现类。 */
    static class UserPort implements JobPort {

        private final Set<String> names = new LinkedHashSet<>();

        @Override
        public void register(JobDefinition definition, JobHandler handler) {
            names.add(definition.name());
        }

        @Override
        public Set<String> registeredJobs() {
            return Set.copyOf(names);
        }
    }
}
