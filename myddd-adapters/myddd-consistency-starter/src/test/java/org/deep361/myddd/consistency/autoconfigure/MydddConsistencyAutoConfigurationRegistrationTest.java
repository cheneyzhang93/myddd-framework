package org.deep361.myddd.consistency.autoconfigure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配类与 {@code AutoConfiguration.imports} 的双向对账（一致性模块侧）。
 *
 * <p>存在的理由与缓存/持久化侧那两条完全相同：本模块的装配测试全部用 {@code AutoConfigurations.of(...)}
 * 直接点名装配类，<b>绕过了 Boot 的注册发现</b>，所以"标了注解却忘了登记"这一类故障在所有装配用例里
 * 都不显形，只有这条守卫抓得住。这条预测在 Task 25 兑现过一次：新增
 * {@code MydddConsistencyLockAopAutoConfiguration} 后、登记 imports 之前，两条用例同时红
 * （第二条报 "some elements were not expected"，第一条报 "could not find the following elements"）。
 * Task 26 兑现了第二次：新增两条幂等装配类后、补 imports 之前，同样的两条同时红，报的形状与第一次逐字相同
 * （一条 "could not find the following elements"、一条 "some elements were not expected"）。Task 27 的
 * DB 分支若再新增装配类，仍是这一处对账。
 */
class MydddConsistencyAutoConfigurationRegistrationTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String AUTOCONFIGURE_PACKAGE = "org.deep361.myddd.consistency.autoconfigure";

    @Test
    void should_register_every_auto_configuration_class_and_nothing_else() {
        List<String> declared = declaredClassNames();
        List<String> annotated = annotatedClassNames();

        // 存在性断言先于相等断言：目录读不到会让两边都空，空对空照样"通过"。
        assertThat(annotated).as("包 %s 里必须发现装配类", AUTOCONFIGURE_PACKAGE).isNotEmpty();
        assertThat(declared).doesNotHaveDuplicates();
        assertThat(declared).containsExactlyInAnyOrderElementsOf(annotated);
    }

    @Test
    void should_anchor_the_discovered_set_to_the_assembly_classes_we_ship() {
        assertThat(annotatedClassNames()).containsExactly(MydddConsistencyAutoConfiguration.class.getName(),
                MydddConsistencyIdempotentAopAutoConfiguration.class.getName(),
                MydddConsistencyIdempotentAutoConfiguration.class.getName(),
                MydddConsistencyLockAopAutoConfiguration.class.getName());
    }

    private static List<String> declaredClassNames() {
        try (InputStream in = new ClassPathResource(IMPORTS_RESOURCE).getInputStream()) {
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (IOException failure) {
            throw new IllegalStateException("读不到 " + IMPORTS_RESOURCE, failure);
        }
    }

    private static List<String> annotatedClassNames() {
        return topLevelClassNames().stream()
                .filter(name -> load(name).isAnnotationPresent(AutoConfiguration.class))
                .sorted()
                .toList();
    }

    private static List<String> topLevelClassNames() {
        Path packageDirectory = packageDirectory();
        try (var entries = Files.list(packageDirectory)) {
            return entries.map(entry -> entry.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(".class"))
                    // 内部类（含嵌套的 @Configuration 分支）不可能是装配类
                    .filter(fileName -> !fileName.contains("$"))
                    .map(fileName -> AUTOCONFIGURE_PACKAGE + '.'
                            + fileName.substring(0, fileName.length() - ".class".length()))
                    .toList();
        } catch (IOException failure) {
            throw new IllegalStateException("列不出 " + packageDirectory, failure);
        }
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, MydddConsistencyAutoConfigurationRegistrationTest.class
                    .getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("class 文件列出来了却加载不到：" + className, failure);
        }
    }

    private static Path packageDirectory() {
        Path classesRoot;
        try {
            classesRoot = Path.of(MydddConsistencyAutoConfiguration.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IllegalStateException("拿不到本模块的 classes 根目录", failure);
        }
        Path directory = classesRoot.resolve(AUTOCONFIGURE_PACKAGE.replace('.', '/'));
        assertThat(Files.isDirectory(directory))
                .as("%s 必须是 target/classes 下真实存在的目录，否则本守卫会退化成空对空", directory)
                .isTrue();
        return directory;
    }
}
