package org.deep361.myddd.testing.integration;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.Inherited;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * {@link MydddIntegrationTest} 的拾取契约：注解必须齐、且真的被 JUnit/Spring 的元注解机制生效。
 */
class MydddIntegrationTestContractTest {

    @MydddIntegrationTest
    static class Sample {
    }

    @Test
    void should_meta_annotate_springboot_test_and_disabled_without_docker() {
        SpringBootTest boot = AnnotatedElementUtils.findMergedAnnotation(Sample.class, SpringBootTest.class);
        assertThat(boot).as("@SpringBootTest 必须通过元注解被找到").isNotNull();
        assertThat(boot.webEnvironment()).as("纯上下文环境：不要求 spring-web 在 classpath 上")
                .isEqualTo(SpringBootTest.WebEnvironment.NONE);
        Testcontainers containers = AnnotatedElementUtils.findMergedAnnotation(Sample.class, Testcontainers.class);
        assertThat(containers).as("@Testcontainers 必须通过元注解被找到").isNotNull();
        assertThat(containers.disabledWithoutDocker()).as("无 Docker 必须跳过而不是失败").isTrue();
    }

    @Test
    void should_be_inheritable_by_test_subclasses() {
        assertThat(MydddIntegrationTest.class.getAnnotation(Inherited.class)).isNotNull();
        class Sub extends Sample {
        }
        assertThat(AnnotatedElementUtils.findMergedAnnotation(Sub.class, SpringBootTest.class)).isNotNull();
    }

    @Test
    void should_not_smuggle_in_imports_profiles_or_extensions() {
        List<String> declared = java.util.Arrays.stream(MydddIntegrationTest.class.getDeclaredAnnotations())
                .map(a -> a.annotationType().getSimpleName())
                .toList();
        assertThat(declared).containsExactlyInAnyOrder("Target", "Retention", "Documented", "Inherited",
                "SpringBootTest", "Testcontainers");
    }

    @Test
    void should_skip_the_whole_class_without_docker_instead_of_failing() {
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request().selectors(selectClass(SampleMysqlIT.class)).build(), listener);
        var summary = listener.getSummary();
        assertThat(summary.getTotalFailureCount()).as("跳过 ≠ 失败；失败明细 %s", summary.getFailures()).isZero();
        assertThat(summary.getTestsSkippedCount() + summary.getTestsStartedCount())
                .as("无 Docker 时该 IT 被跳过，有 Docker 时被启动").isEqualTo(1);
    }
}
