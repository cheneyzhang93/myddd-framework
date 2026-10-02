package org.deep361.myddd.feign.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配类与 {@code AutoConfiguration.imports} 的双向对账（与 persistence / cache / consistency 三个
 * starter 同一形态的守卫，理由在那三份 javadoc 里已经付过学费，这里只补一句本模块特有的风险）：
 * 本模块只有 <b>一个</b>装配类，一旦它没登记，"依赖即启用"（§0.3-5）这件事会整体失效而所有装配测试照绿
 * ——{@link MydddFeignAutoConfigurationTest} 用 {@code AutoConfigurations.of(...)} 直接点名，绕过了注册发现。
 *
 * <p>用"列 target/classes 目录 + 反射问注解"而不是 Spring 的类路径扫描器：实测（Task 21 验证轮）
 * {@code ClassPathScanningCandidateComponentProvider} 会漏掉某些带 {@code @AutoConfiguration} 的类，
 * 对账守卫的立身之本是"一个都不能漏"。
 */
class MydddFeignAutoConfigurationRegistrationTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String AUTOCONFIGURE_PACKAGE = "org.deep361.myddd.feign.autoconfigure";

    @Test
    void should_register_every_auto_configuration_class_and_nothing_else() {
        List<String> annotated = annotatedClassNames();

        assertThat(annotated).as("包 %s 里必须发现装配类", AUTOCONFIGURE_PACKAGE).isNotEmpty();
        assertThat(declaredClassNames()).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(annotated);
    }

    @Test
    void should_anchor_the_discovered_set_to_the_assembly_classes_we_ship() {
        assertThat(annotatedClassNames()).containsExactly(MydddFeignAutoConfiguration.class.getName());
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
            return Class.forName(className, false, MydddFeignAutoConfigurationRegistrationTest.class.getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("class 文件列出来了却加载不到：" + className, failure);
        }
    }

    private static Path packageDirectory() {
        Path classesRoot;
        try {
            classesRoot = Path.of(MydddFeignAutoConfiguration.class.getProtectionDomain()
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
