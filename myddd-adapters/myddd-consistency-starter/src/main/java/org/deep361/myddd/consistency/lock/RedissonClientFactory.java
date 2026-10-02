package org.deep361.myddd.consistency.lock;

import java.util.List;
import java.util.function.Consumer;
import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyProperties;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.Node;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.Standalone;

/**
 * 把 Boot 解析好的 Redis 连接参数翻译成 Redisson 的 {@link Config}，并据此建出客户端。
 *
 * <p><b>连接参数的唯一真源仍是 {@code spring.data.redis.*}</b>（§0.3-5：不新增、不改名、不重钉三方键）。
 * 我们消费的是 {@link DataRedisConnectionDetails}——Boot 给它的所有消费方（Lettuce、Jedis、
 * Testcontainers 的 {@code @ServiceConnection}、docker compose）解析完的那份视图，而不是自己再读一遍
 * 属性：自己读会漏掉容器与 compose 那两条覆盖路径，落地表现为"测试里连不上、生产里连错实例"。
 *
 * <p><b>四种形态支持三种，第四种快速失败。</b>standalone / sentinel / cluster 都翻译得过去。
 * {@code master-replica} 不：一手实测（javap {@code spring-boot-data-redis-4.1.1.jar}）
 * {@code DataRedisConnectionDetails.MasterReplica} 只有 {@code getNodes()}——一张<b>没有主从归属</b>的
 * 扁平节点表，而 Redisson 的 {@code MasterSlaveServersConfig} 必须指名 {@code setMasterAddress}。
 * "猜第一个是主"能编过、能在测试里跑绿，却在主被切换后连到一台从库上写锁，所以这里抛
 * {@link FrameworkException} 并说明支持哪三种。
 *
 * <p><b>凭据写在 {@link Config} 上，不写在形态子配置上</b>（一手实测：对
 * {@code redisson-4.7.0.jar} 跑 {@code javap -c org.redisson.connection.MasterSlaveConnectionManager}，
 * 连接配置生成的是 {@code RedisClientConfig.setUsername(config.getUsername())}，取不到才退回
 * {@code MasterSlaveServersConfig.getUsername()}；而 {@code BaseConfig.setUsername/setPassword} 带
 * {@code @Deprecated}，每次调用还会打一条 {@code username setting is deprecated…} 的 WARN）。
 * 于是三种形态共用一条凭据写入路径，启动日志里也没有我方制造的 WARN。
 * 哨兵的 {@code sentinelUsername/sentinelPassword} 是另一套坐标（认证对象是哨兵而不是数据节点），
 * 顶层没有对应字段，仍留在 {@code SentinelServersConfig} 上。
 *
 * <p><b>{@link #create} 会真的建立连接（一手实测）</b>：{@code Config.isLazyInitialization()} 的缺省是
 * {@code false}，{@code Redisson.create(config)} 在连不上时约 7 秒后抛
 * {@code RedisConnectionException: Unable to connect to Redis server: <host>/<ip>:<port>}。
 * 这是刻意保留的形态：配错主机名应当在<b>启动期</b>炸，而不是在第一次拿锁时炸。
 * 打开 {@code setLazyInitialization(true)} 确实能让建 bean 离线成功（实测 65 ms），但失败会推迟到第一条命令
 * （实测约 6.8 s 后抛 {@code RedisException: Unexpected exception while processing command}），
 * 把"启动就能发现的配置错误"换成"线上第一次加锁才发现"——不划算，所以本框架不开。
 *
 * <p><b>{@code lockWatchdogTimeout} 总会被写进 {@link Config}</b>，哪怕业务一次也没用
 * {@code leaseTime == 0}（R3）。它是 Redisson 客户端级的设置，读它的时机在建连接时而不是拿锁时，
 * 因此 {@link MydddConsistencyProperties.Lock#getWatchdogTimeout()} 的校验无条件生效。
 */
public final class RedissonClientFactory {

    private RedissonClientFactory() {
    }

    /**
     * 只翻译、不连接。这条方法存在的全部理由是可测：它把"三种形态怎么映射"变成一份能在 surefire 里逐条
     * 断言的清单，不需要 Docker、不需要真 Redis。
     *
     * @param details    Boot 解析出的连接参数（{@code spring.data.redis.*} 的视图）
     * @param properties 本能力的配置，这里用到 {@code lock.watchdog-timeout}
     * @return 可直接交给 {@code Redisson.create} 的配置
     */
    public static Config buildConfig(DataRedisConnectionDetails details, MydddConsistencyProperties properties) {
        Config config = new Config();
        config.setLockWatchdogTimeout(properties.getLock().getWatchdogTimeout().toMillis());
        // 数据节点凭据写在 Config 上而不是各形态的子配置上：一手实测（javap -c
        // org.redisson.connection.MasterSlaveConnectionManager）连接配置由
        // RedisClientConfig.setUsername(config.getUsername()) 生成，子配置里的同名字段只是兜底；
        // 而子配置那对 setter 在 4.7.0 已 @Deprecated 且每次调用打一条 WARN 日志。
        // 写在顶层既走的是官方推荐的形状，也让"配了凭据却没生效"这件事在读回 Config.getUsername() 时无处藏。
        acceptIfPresent(details.getUsername(), config::setUsername);
        acceptIfPresent(details.getPassword(), config::setPassword);
        String scheme = scheme(details);
        if (details.getCluster() != null) {
            ClusterServersConfig cluster = config.useClusterServers();
            for (Node node : requireNodes(details.getCluster().getNodes(), "spring.data.redis.cluster.nodes")) {
                cluster.addNodeAddress(address(scheme, node));
            }
            return config;
        }
        if (details.getSentinel() != null) {
            DataRedisConnectionDetails.Sentinel sentinel = details.getSentinel();
            SentinelServersConfig servers = config.useSentinelServers();
            servers.setMasterName(requireMasterName(sentinel.getMaster()));
            // 哨兵自己的凭据是另一套坐标（问"主在哪"用的认证，不是连数据节点用的），
            // 它只有子配置形态，因此留在 SentinelServersConfig 上。
            acceptIfPresent(sentinel.getUsername(), servers::setSentinelUsername);
            acceptIfPresent(sentinel.getPassword(), servers::setSentinelPassword);
            for (Node node : requireNodes(sentinel.getNodes(), "spring.data.redis.sentinel.nodes")) {
                servers.addSentinelAddress(address(scheme, node));
            }
            servers.setDatabase(sentinel.getDatabase());
            return config;
        }
        if (details.getMasterReplica() != null) {
            throw new FrameworkException("本框架的锁不支持 spring.data.redis.masterreplica 形态："
                    + "Boot 的 DataRedisConnectionDetails.MasterReplica 只暴露 getNodes()（一手实测，"
                    + "javap spring-boot-data-redis-4.1.1），一张没有主从归属的节点表翻不出 Redisson 必需的"
                    + " setMasterAddress，猜第一个当主库会在主被切换后连到从库上写锁而不报错。"
                    + "请改用 standalone（spring.data.redis.host）、sentinel（spring.data.redis.sentinel.*）"
                    + "或 cluster（spring.data.redis.cluster.*），"
                    + "或者关掉本能力：myddd.consistency.lock.enabled=false");
        }
        Standalone standalone = details.getStandalone();
        if (standalone == null) {
            throw new FrameworkException("DataRedisConnectionDetails 既没有 standalone 也没有 sentinel/cluster："
                    + "redis 自动装配在场却没有任何连接形态，锁客户端无从建立。"
                    + "请配 spring.data.redis.host（或对应的 cluster/sentinel 键），"
                    + "或者关掉本能力：myddd.consistency.lock.enabled=false");
        }
        SingleServerConfig single = config.useSingleServer();
        single.setAddress(address(scheme,
                requireNode(standalone.getHost(), standalone.getPort(), "spring.data.redis.host")));
        single.setDatabase(standalone.getDatabase());
        return config;
    }

    /**
     * 建出客户端。<b>这一步会真连接</b>（见类注释的实测数字），因此它只出现在装配路径上，
     * 单元侧的证据全在 {@link #buildConfig}。
     */
    public static RedissonClient create(DataRedisConnectionDetails details, MydddConsistencyProperties properties) {
        return Redisson.create(buildConfig(details, properties));
    }

    /**
     * 只有 Boot 给出了 {@code SslBundle} 时才用 {@code rediss://}。
     *
     * <p>{@link DataRedisConnectionDetails} 在 4.1.1 没有"ssl 开没开"的开关（一手实测：接口只有
     * {@code getUsername/getPassword/getSslBundle/getStandalone/getSentinel/getCluster/getMasterReplica}
     * 七个方法），bundle 非空就是唯一可读的信号。走 TLS 时地址协议必须跟着换，否则在只监听 TLS 的实例上
     * 表现为"连接被重置"而不是"协议不支持"。
     */
    private static String scheme(DataRedisConnectionDetails details) {
        return details.getSslBundle() == null ? "redis" : "rediss";
    }

    private static String address(String scheme, Node node) {
        return scheme + "://" + node.host() + ":" + node.port();
    }

    /**
     * 缺席的凭据跳过而不是写进去：Boot 在"没配密码"时给 {@code null}，而"键写了却留空"给的是空串。
     * 把空串交给 Redisson 等于"用空密码认证"——在未开认证的实例上恰好可用，在开了 ACL 的实例上是 AUTH
     * 参数错误，两种结果都不该由一个空串决定。
     */
    private static void acceptIfPresent(String value, Consumer<String> setter) {
        if (value != null && !value.isEmpty()) {
            setter.accept(value);
        }
    }

    private static List<Node> requireNodes(List<Node> nodes, String keyHint) {
        if (nodes == null || nodes.isEmpty()) {
            throw new FrameworkException(keyHint + " 解析出来是空的：键写了却没给出任何节点，"
                    + "Redisson 会在没有任何地址的情况下建出一个连不上任何地方的客户端。"
                    + "请给出至少一个 host:port");
        }
        return nodes;
    }

    private static Node requireNode(String host, int port, String keyHint) {
        if (host == null || host.isBlank()) {
            throw new FrameworkException(keyHint + " 是空白：standalone 形态的锁客户端没有主机名可连。"
                    + "请给出主机名，或者关掉本能力：myddd.consistency.lock.enabled=false");
        }
        return new Node(host, port);
    }

    private static String requireMasterName(String master) {
        if (master == null || master.isBlank()) {
            throw new FrameworkException("spring.data.redis.sentinel.master 不能是空白：sentinel 形态下"
                    + "它是“找对主库”的唯一坐标，没有它 Redisson 连哨兵都没法问出主在哪。"
                    + "请给出 master 名，或者关掉本能力：myddd.consistency.lock.enabled=false");
        }
        return master;
    }
}
