package org.deep361.myddd.persistence.autoconfigure;

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
 * 装配类与 {@code AutoConfiguration.imports} 的双向对账。
 *
 * <p><b>这条守卫为什么存在</b>：一个标了 {@link AutoConfiguration} 而忘了登记进 imports 的类，表现是
 * "编译通过、测试全绿、能力整块静默缺失"——本模块的装配测试全部用 {@code AutoConfigurations.of(...)}
 * 直接点名装配类，<b>绕过了 Boot 的注册发现机制</b>，所以"没登记"这件事在这一层的所有用例里都不显形。
 * Task 21 还要往这个包里加死信读侧的装配类，那条路径迟早会被踩到。
 *
 * <p><b>为什么用"列目录 + 反射"而不是 Spring 的类路径扫描器</b>：一手实测（本任务验证轮）——
 * {@code ClassPathScanningCandidateComponentProvider} 配 {@code AnnotationTypeFilter(AutoConfiguration.class)}
 * 扫 {@code ...persistence.autoconfigure} 只返回 {@code MydddPersistenceAutoConfiguration} 与
 * {@code MydddOutboxStoreAutoConfiguration} 两个，<b>漏掉了 {@code MydddRoutingDataSourceAutoConfiguration}</b>；
 * 而同一个 ASM {@code MetadataReader} 读这个类的 class 文件，{@code isAnnotated(AutoConfiguration)} 返回
 * {@code true}、{@code isIndependent()}/{@code isConcrete()} 也都为真。也就是说漏检发生在 provider 内部，
 * 与类本身的可判定特征无关。对账守卫的立身之本是"一个都不能漏"，所以这里用能证明正确的路子：
 * 列出装配包目录里的 class 文件，逐个 {@code Class.forName(name, false, loader)} 之后问注解。
 *
 * <p><b>反向（imports 里写了不存在或没标注解的名字）同样钉住</b>：Boot 读 imports 时对加载不到的类名会直接
 * 让应用启动失败，写错一个字母就是一次全量启动事故；而"没登记"是沉默，两条都必须在这一层拦住。
 *
 * <p>对照已交付夹具：{@code MydddDomainAutoConfigurationTest} 用的是"显式列举三个类名"的单向写法，
 * 那种写法只能钉住"登记的顺序和内容没被人改坏"，钉不住"新装配类忘了登记"。
 */
class MydddPersistenceAutoConfigurationRegistrationTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String AUTOCONFIGURE_PACKAGE = "org.deep361.myddd.persistence.autoconfigure";

    @Test
    void should_register_every_auto_configuration_class_and_nothing_else() {
        List<String> declared = declaredClassNames();
        List<String> annotated = annotatedClassNames();

        // 目录读不到（路径拼错、跑在 jar 里而不是 target/classes）会让两边都空，空对空照样"通过"，
        // 所以存在性断言必须先于相等断言。
        assertThat(annotated).as("包 %s 里必须发现装配类", AUTOCONFIGURE_PACKAGE).isNotEmpty();
        assertThat(declared).doesNotHaveDuplicates();
        // imports 文件的行序不参与 Boot 的装配排序（排序看 @AutoConfiguration 的 before/after），
        // 所以这一条只要求集合相等，不要求同序。
        assertThat(declared).containsExactlyInAnyOrderElementsOf(annotated);
    }

    @Test
    void should_anchor_the_discovered_set_to_the_assembly_classes_we_ship() {
        // 上一条是"两侧一致"，本条是"一致的两侧必须正好是我们交付的那几个"：有人把某个装配类的
        // @AutoConfiguration 注解连同 imports 里那一行一起删掉时，上一条会空对空地通过，这条不会。
        // 顺序是类名字典序（annotatedClassNames() 自己排过）。
        // Task 21 加进死信读侧装配类时，正是这条先红（实测：Expected 三个、actual 四个），
        // 而上一条只报"两侧一致"——两道的分工就在这里。
        assertThat(annotatedClassNames()).containsExactly(
                MydddOutboxDeadLetterAutoConfiguration.class.getName(),
                MydddOutboxStoreAutoConfiguration.class.getName(),
                MydddPersistenceAutoConfiguration.class.getName(),
                MydddRoutingDataSourceAutoConfiguration.class.getName());
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

    /** 装配包目录里的顶层类，按注解筛出装配类，返回类名字典序列表。 */
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
                    // 内部类（含 Properties 的七个嵌套配置类）不可能是装配类
                    .filter(fileName -> !fileName.contains("$"))
                    .map(fileName -> AUTOCONFIGURE_PACKAGE + '.'
                            + fileName.substring(0, fileName.length() - ".class".length()))
                    .toList();
        } catch (IOException failure) {
            throw new IllegalStateException("列不出 " + packageDirectory, failure);
        }
    }

    /**
     * 用本测试自己的 {@code ClassLoader}、且 {@code initialize=false} 加载：装配类的静态初始化不该把
     * "发现"这一步带崩，也不该因为容器里配了别的 classloader 就扫不到类。
     */
    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, MydddPersistenceAutoConfigurationRegistrationTest.class
                    .getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("class 文件列出来了却加载不到：" + className, failure);
        }
    }

    private static Path packageDirectory() {
        Path classesRoot;
        try {
            classesRoot = Path.of(MydddPersistenceAutoConfiguration.class.getProtectionDomain()
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
