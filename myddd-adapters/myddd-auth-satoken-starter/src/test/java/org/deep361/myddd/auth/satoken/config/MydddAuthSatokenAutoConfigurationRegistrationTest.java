package org.deep361.myddd.auth.satoken.config;

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
 * 装配类与 {@code AutoConfiguration.imports} 的双向对账（satoken provider 侧）。
 *
 * <p>存在的理由与壳侧那两条完全相同：本模块的装配用例全部用 {@code AutoConfigurations.of(...)}
 * 直接点名装配类，<b>绕过了 Boot 的注册发现</b>，"标了注解却忘了登记那一行"在 {@code AC1}–{@code AC7}
 * 里一条都不显形，而它恰恰是应用侧唯一会撞上的形态（业务只引坐标，从不 {@code @Import} 框架的装配类）。
 * 这个缺口在 Plan 3 兑现过两次（Task 25、Task 26：新增装配类后、补 imports 前，两条用例同时红，
 * 报的形状分别是 "could not find the following elements" 与 "some elements were not expected"）。
 */
class MydddAuthSatokenAutoConfigurationRegistrationTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String AUTOCONFIGURE_PACKAGE = "org.deep361.myddd.auth.satoken.config";

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
        assertThat(annotatedClassNames()).containsExactly(MydddAuthSatokenAutoConfiguration.class.getName());
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
            return Class.forName(className, false,
                    MydddAuthSatokenAutoConfigurationRegistrationTest.class.getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("class 文件列出来了却加载不到：" + className, failure);
        }
    }

    private static Path packageDirectory() {
        Path classesRoot;
        try {
            classesRoot = Path.of(MydddAuthSatokenAutoConfiguration.class.getProtectionDomain()
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
