package org.deep361.myddd.testing.container;

import com.github.dockerjava.api.model.PortBinding;
import org.deep361.myddd.testing.image.DevboxImages;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * devbox 同源 RocketMQ（namesrv + broker）容器对，供消息适配器的真投递集成测试使用。
 *
 * <p>Testcontainers 2.0.5 的 62 个官方模块里**没有 RocketMQ**，所以这里直接用 {@link GenericContainer} 自持。
 * 两个铁律：
 * <ol>
 *   <li><b>固定宿主端口</b>（9876/10911）。broker 向 namesrv 公告的是 {@code brokerIP1:10911}，
 *       而 {@code brokerIP1} 在配置文件里写死成 {@code 127.0.0.1}（宿主侧客户端视角），
 *       所以宿主端口必须等于容器端口，随机映射会让客户端拿到连不上的地址。</li>
 *   <li><b>容器内访问 namesrv 走 {@code host.docker.internal}</b>：broker 与 namesrv 各有固定宿主端口，
 *       broker 通过宿主回环连 namesrv，避免"两个容器自建网络 + 容器 IP 公告"那条走不通的路。
 *       Linux 上 {@code host-gateway} 由 {@code --add-host} 落地，Docker Desktop 原生可解析。</li>
 * </ol>
 *
 * <p>刻意<b>不给等待策略</b>：{@code apache/rocketmq:5.3.2} 镜像里既没有 {@code nc} 也没有 {@code ss/netstat}
 * （devbox compose 的 healthcheck 注释就是那次实测记录），{@code Wait.forHealthCheck()} 在这上面恒假；
 * 默认 {@code Wait.forListeningPort()} 已经在等 9876/10911，够用。
 *
 * <p>broker 配置复用 devbox 正本（{@code classpath:/rocketmq/it-broker.conf}），
 * 由 {@code DevboxImagesTest#should_keep_the_it_broker_conf_byte_identical_to_devbox()} 钉住不许漂移。
 *
 * <p>使用方式：测试类标 {@code @MydddIntegrationTest}（内含 {@code @Testcontainers}），然后
 * {@code MydddRocketMqContainer.NAMESRV/BROKER} 由 JUnit 扩展按静态字段声明顺序启动（namesrv 在前）。
 */
public final class MydddRocketMqContainer {

    /** namesrv 的宿主/容器端口（与 devbox compose 同值）。 */
    public static final int NAMESRV_PORT = 9876;
    /** broker 的宿主/容器端口（与 devbox compose 同值；VIP 通道已由配置关掉，只需这一个）。 */
    public static final int BROKER_PORT = 10911;
    /** 容器内访问宿主回环的主机名。 */
    static final String HOST_LOOPBACK = "host.docker.internal";
    private static final String BROKER_CONF_PATH = "/tmp/myddd-it-broker.conf";

    @Container
    public static final GenericContainer<?> NAMESRV = new GenericContainer<>(DevboxImages.dockerImage("rocketmq"))
            .withExtraHost(HOST_LOOPBACK, "host-gateway")
            .withCommand("sh", "mqnamesrv")
            .withExposedPorts(NAMESRV_PORT)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withPortBindings(PortBinding.parse(NAMESRV_PORT + ":" + NAMESRV_PORT)))
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    public static final GenericContainer<?> BROKER = new GenericContainer<>(DevboxImages.dockerImage("rocketmq"))
            .withExtraHost(HOST_LOOPBACK, "host-gateway")
            .withEnv("NAMESRV_ADDR", HOST_LOOPBACK + ":" + NAMESRV_PORT)
            .withCopyFileToContainer(MountableFile.forClasspathResource("/rocketmq/it-broker.conf"), BROKER_CONF_PATH)
            .withCommand("sh", "mqbroker", "-c", BROKER_CONF_PATH)
            .withExposedPorts(BROKER_PORT)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withPortBindings(PortBinding.parse(BROKER_PORT + ":" + BROKER_PORT)))
            .withStartupTimeout(Duration.ofMinutes(3));

    private MydddRocketMqContainer() {
    }

    /** 宿主侧客户端（我们的 Java 客户端就跑在宿主 JVM 里）连接 namesrv 用的地址。 */
    public static String namesrvAddr() {
        return "127.0.0.1:" + NAMESRV_PORT;
    }
}
