package org.deep361.myddd.cache.autoconfigure;

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
 * 装配类与 {@code AutoConfiguration.imports} 的双向对账（缓存模块侧）。
 *
 * <p>存在的理由与持久化侧那一条完全相同（见 {@code MydddPersistenceAutoConfigurationRegistrationTest} 的长注释）：
 * 本模块的装配测试全部用 {@code AutoConfigurations.of(...)} 直接点名装配类，<b>绕过了 Boot 的注册发现</b>，
 * 所以"标了注解却忘了登记"这一类故障在这一层的所有用例里都不显形，只有这条守卫抓得住。
 *
 * <p>Task 23 会往这个包里加 L2 与两级管理器的装配类。届时第二条用例先红，按新增类名补进字面清单——
 * 那条纪律已经在持久化侧走过两次（3 类 → 4 类）。
 */
class MydddCacheAutoConfigurationRegistrationTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String AUTOCONFIGURE_PACKAGE = "org.deep361.myddd.cache.autoconfigure";

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
        assertThat(annotatedClassNames()).containsExactly(MydddCacheAutoConfiguration.class.getName());
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
                    // 内部类（含 Type 枚举与嵌套配置类）不可能是装配类
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
            return Class.forName(className, false, MydddCacheAutoConfigurationRegistrationTest.class
                    .getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("class 文件列出来了却加载不到：" + className, failure);
        }
    }

    private static Path packageDirectory() {
        Path classesRoot;
        try {
            classesRoot = Path.of(MydddCacheAutoConfiguration.class.getProtectionDomain()
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
