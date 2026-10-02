package org.deep361.myddd.testing.image;

import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * devbox 服务镜像清单：集成测试启动的容器一律从这里取镜像引用，本仓库与业务工程都不允许另写 tag。
 *
 * <p><b>唯一真源</b>是父 POM 的 {@code myddd.devbox.image.*} 属性；值经 Maven 资源过滤写进 classpath 根的
 * {@code myddd-devbox-images.properties}。换镜像只需要改 POM 一处，devbox compose 与本类自动同步，
 * 三处任一漂移由 {@code DevboxImagesTest} 拦下。
 *
 * <p>失败策略与框架一致：配置错了就在第一次使用时抛清楚的 {@link IllegalStateException}，
 * 不静默退回默认镜像（那会让"测的镜像"与"跑的镜像"悄悄不一致）。
 */
public final class DevboxImages {

    /** 清单项资源名（包私有常量：测试与 {@link #read} 共用同一个字符串，避免两处拼写）。 */
    static final String RESOURCE = "/myddd-devbox-images.properties";

    private static final Properties VALUES = read(DevboxImages.class.getResourceAsStream(RESOURCE));

    /** devbox 的 MySQL 镜像引用，例如 {@code mysql:8.4}。 */
    public static final String MYSQL = of("mysql");
    /** devbox 的 Redis 镜像引用，例如 {@code redis:7.4-alpine}。 */
    public static final String REDIS = of("redis");
    /** devbox 的 Kafka（KRaft 单节点）镜像引用，例如 {@code apache/kafka:4.0.0}。 */
    public static final String KAFKA = of("kafka");
    /** devbox 的 RocketMQ 镜像引用（namesrv 与 broker 同一个镜像，实测 {@code apache/rocketmq:5.3.2}）。 */
    public static final String ROCKETMQ = of("rocketmq");
    /** devbox 的 XXL-Job admin 镜像引用（实测 {@code xuxueli/xxl-job-admin:3.4.2}）。 */
    public static final String XXLJOB_ADMIN = of("xxljob-admin");

    private DevboxImages() {
    }

    /** 清单里的全部键（升序不可变副本）。 */
    public static Set<String> keys() {
        return Set.copyOf(new TreeSet<>(VALUES.stringPropertyNames()));
    }

    /**
     * 按键取镜像引用（已去空白）。
     *
     * @throws IllegalArgumentException 清单不含该键
     * @throws IllegalStateException    该键的值为空白或未被资源过滤替换
     */
    public static String of(String key) {
        if (!VALUES.containsKey(key)) {
            throw new IllegalArgumentException("devbox 镜像清单不含 " + key + "，可用键 " + keys());
        }
        return requireValue(key, VALUES.getProperty(key));
    }

    /** {@link #of(String)} 的便捷包装：直接得到容器类需要的 {@link DockerImageName}。 */
    public static DockerImageName dockerImage(String key) {
        return DockerImageName.parse(of(key));
    }

    /** 值校验（包私有，便于直接单测坏值分支）。 */
    static String requireValue(String key, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "devbox 镜像 " + key + " 为空白：请检查 " + RESOURCE + " 是否被资源过滤正确写入");
        }
        String value = raw.trim();
        if (value.contains("@")) {
            throw new IllegalStateException("devbox 镜像 " + key + " 未被资源过滤（实际值 " + value + "）："
                    + RESOURCE + " 必须写成 @myddd.devbox.image." + key + "@ 并由 maven-resources-plugin 替换");
        }
        return value;
    }

    /** 读清单资源（包私有，便于单测"资源缺失"分支）。 */
    static Properties read(InputStream in) {
        if (in == null) {
            throw new IllegalStateException("classpath 缺少 " + RESOURCE
                    + "：myddd-test-starter 的镜像清单没有随 jar 发布（构建配置问题，不是用户配置问题）");
        }
        Properties props = new Properties();
        try (InputStream stream = in) {
            props.load(stream);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + RESOURCE + " 失败", e);
        }
        return props;
    }
}
