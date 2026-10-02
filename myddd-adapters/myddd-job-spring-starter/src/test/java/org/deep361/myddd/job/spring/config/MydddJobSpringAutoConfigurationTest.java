package org.deep361.myddd.job.spring.config;

import org.deep361.myddd.job.spring.MydddJobScanner;
import org.deep361.myddd.job.spring.SpringJobPort;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.deep361.myddd.kernel.port.job.JobHandler;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.deep361.myddd.kernel.port.job.MydddJob;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动装配面（{@code AC} 组）：全部用 {@code ApplicationContextRunner}，不起网络；调度器惰性启动，不起线程。 */
class MydddJobSpringAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddJobSpringAutoConfiguration.class));

    /** AC1：零配置装配出两个 bean，且不碰 TaskScheduler 这个名字。 */
    @Test
    void should_assemble_the_port_and_the_scanner_without_publishing_a_task_scheduler() {
        runner.run(context -> {
            assertTrue(context.getBeanNamesForType(SpringJobPort.class).length == 1);
            assertTrue(context.getBeanNamesForType(MydddJobScanner.class).length == 1);
            assertTrue(context.getBeanNamesForType(TaskScheduler.class).length == 0,
                    "ThreadPoolTaskScheduler 不许作为 bean 暴露：它的 getPoolSize() 在 initialize 之后会说谎（S2-Q5）");
        });
    }

    /** AC2：总开关关掉时一个 bean 都不产。 */
    @Test
    void should_produce_nothing_when_the_capability_is_disabled() {
        runner.withPropertyValues("myddd.job.enabled=false").run(context -> {
            assertTrue(context.getBeansOfType(SpringJobPort.class).isEmpty());
            assertTrue(context.getBeansOfType(MydddJobScanner.class).isEmpty());
            assertTrue(context.getBeansOfType(MydddJobSpringProperties.class).isEmpty(),
                    "enabled=false 由条件类挡住，连属性 bean 都不该被注册（条件挂在自动配置类上，不是挂在 @Bean 方法上）");
        });
    }

    /** AC3：显式选中——选中自己装配，选中别人让位。 */
    @Test
    void should_assemble_only_for_the_provider_actually_selected() {
        runner.withPropertyValues("myddd.job.provider=spring")
                .run(context -> assertTrue(context.getBeanNamesForType(SpringJobPort.class).length == 1));
        runner.withPropertyValues("myddd.job.provider=xxljob")
                .run(context -> assertTrue(context.getBeansOfType(SpringJobPort.class).isEmpty(),
                        "显式选了 xxljob，spring 侧必须一个 bean 都不产"));
    }

    /** AC4：池大小走真绑定路径时也要快速失败，并且说得出键名。 */
    @Test
    void should_fail_startup_with_the_offending_key_when_the_pool_size_is_illegal() {
        runner.withPropertyValues("myddd.job.spring.pool-size=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.job.spring.pool-size");
        });
    }

    /** AC5：用户自带端口时端口让位，扫描器跟着注到用户那个端口上（形参写成 SpringJobPort 的话这里不是让位，是崩）。 */
    @Test
    void should_step_aside_for_a_user_supplied_job_port_but_keep_scanning() {
        runner.withUserConfiguration(UserPortConfiguration.class)
                .withBean(SettleJob.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertTrue(context.getBeansOfType(SpringJobPort.class).isEmpty(),
                            "@ConditionalOnMissingBean(JobPort.class) 失效：用户自己写的端口被我们的实现顶掉了");
                    assertTrue(context.getBean(UserPort.class).registeredJobs().contains("nightly-settle"),
                            "端口让位之后扫描器必须跟着注到用户那个端口上");
                });
    }

    /** AC6：两个 bean 的名字是排障出口，必须逐字稳定。 */
    @Test
    void should_publish_the_two_beans_under_documented_names() {
        runner.run(context -> {
            assertInstanceOf(SpringJobPort.class, context.getBean("mydddJobPort"));
            assertInstanceOf(MydddJobScanner.class, context.getBean("mydddJobScanner"));
        });
    }

    /** AC7：容器关闭把端口关掉，且关掉之后拒绝注册而不是静默收下。 */
    @Test
    void should_close_the_port_when_the_context_closes() {
        ConfigurableApplicationContext context =
                new AnnotationConfigApplicationContext(MydddJobSpringAutoConfiguration.class);
        SpringJobPort port = context.getBean(SpringJobPort.class);
        context.close();
        assertThrows(IllegalStateException.class,
                () -> port.register(JobDefinition.of("after-close", "0 0 3 * * ?"), handlerThatDoesNothing()));
    }

    /**
     * AC8：两个 bean 抢同一任务名 ⇒ 启动失败，且"谁占住这个名字"由 bean 名次级排序决定、与注册次序无关。
     * withBean 故意把 b-dup 写在前面：不排序时先注册的是 0 0 9，下面那条"已注册的表达式"断言就对不上。
     */
    @Test
    void should_report_a_duplicated_job_name_from_two_beans_deterministically() {
        runner.withBean("b-dup", SecondDup.class).withBean("a-dup", FirstDup.class).run(context -> {
            assertThat(context).hasFailed();
            // 扫描发生在 afterSingletonsInstantiated，Spring 不再包一层 BeanCreationException：断的是启动失败
            // 本身，写成 rootCause() 会因为它没有 cause 而直接红（实测 AC8 第一次就是这么红的）。
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dup")
                    .hasMessageContaining("已注册的表达式为 0 0 8 * * ?");
        });
    }

    /** AC9：真实容器里带注解的 bean 会被注册（把扫描时机钉在装配面上）。 */
    @Test
    void should_register_a_annotated_bean_found_in_a_real_context() {
        runner.withUserConfiguration(AnnotatedJobConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            Set<String> registered = context.getBean(SpringJobPort.class).registeredJobs();
            assertTrue(registered.contains("nightly-settle"), "装配完成但任务没进端口：" + registered);
        });
    }

    private static JobHandler handlerThatDoesNothing() {
        return context -> {
        };
    }

    /** AC5 用：用户自己贡献的端口实现（只记名字，不起任何调度）。 */
    static class UserPort implements JobPort {

        private final Set<String> names = new LinkedHashSet<>();

        @Override
        public synchronized void register(JobDefinition definition, JobHandler handler) {
            names.add(definition.name());
        }

        @Override
        public synchronized Set<String> registeredJobs() {
            return Set.copyOf(names);
        }
    }

    @Configuration
    static class UserPortConfiguration {

        @Bean
        UserPort userJobPort() {
            return new UserPort();
        }
    }

    /** AC9 用：真实容器里放一个带注解的任务 bean。 */
    @Configuration
    static class AnnotatedJobConfiguration {

        @Bean
        SettleJob settleJob() {
            return new SettleJob();
        }
    }

    /** AC8 用：两个 bean 抢同一个任务名，cron 不同，且 bean 名的字典序与注册次序相反。 */
    @MydddJob(name = "dup", cron = "0 0 8 * * ?")
    static class FirstDup implements JobHandler {
        @Override
        public void execute(JobContext context) {
        }
    }

    @MydddJob(name = "dup", cron = "0 0 9 * * ?")
    static class SecondDup implements JobHandler {
        @Override
        public void execute(JobContext context) {
        }
    }

    /** AC5/AC9 用：一个普通的带注解任务 bean。 */
    @MydddJob(name = "nightly-settle", cron = "0 0 3 * * ?")
    static class SettleJob implements JobHandler {
        @Override
        public void execute(JobContext context) {
        }
    }
}
