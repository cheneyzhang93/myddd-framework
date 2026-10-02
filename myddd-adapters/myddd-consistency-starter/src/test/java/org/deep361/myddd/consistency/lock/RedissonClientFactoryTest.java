package org.deep361.myddd.consistency.lock;

import java.time.Duration;
import java.util.List;
import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyProperties;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.Node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RedissonClientFactory#buildConfig} 的映射面：Boot 解析出的连接参数怎么变成 Redisson 的配置。
 *
 * <p><b>整组用例都不需要 Docker、也不需要真 Redis</b>：{@code buildConfig} 只做翻译，连接发生在
 * {@code create}。翻译是可枚举的（三种形态 + 一套凭据 + 一个协议前缀 + 一条优先级 + 四种非法配法），
 * 所以这里能逐条钉住；真连接的证据在 {@code LockRedisIT}（要 Docker）与本组最后一条
 * "没人监听就快速失败"的用例里。
 *
 * <p><b>读回配置只能走 {@code useXxx()}</b>（一手实测：{@code Config} 上没有 {@code getXxxServersConfig()}
 * 这类读回方法；{@code useSingleServer()} 第二次调用返回同一个实例，换成另一种形态则抛
 * {@code IllegalStateException: single server config already used!}）。所以每条用例只读自己那一种形态，
 * "选了哪种形态"用 {@code isSingleConfig()/isSentinelConfig()/isClusterConfig()} 断言。
 */
class RedissonClientFactoryTest {

    private static MydddConsistencyProperties properties() {
        return new MydddConsistencyProperties();
    }

    @Test
    void should_map_standalone_host_port_and_database() {
        Config config = RedissonClientFactory.buildConfig(
                StubConnectionDetails.standalone("redis.internal", 6380, 7), properties());

        SingleServerConfig single = config.useSingleServer();
        assertThat(config.isSingleConfig()).isTrue();
        assertThat(single.getAddress()).isEqualTo("redis://redis.internal:6380");
        // 库号只有 standalone 与 sentinel 两种形态带得过去（cluster 没有多库概念）
        assertThat(single.getDatabase()).isEqualTo(7);
    }

    // 唯一能读回子配置那个同名字段的入口就是 BaseConfig.getUsername()/getPassword()，而它们在 4.7.0 是
    // @Deprecated 的。这里读它不是为了用这份凭据，而是为了断言"它没被写"——正因为在读它，才必须压掉警告
    // 而不是改写主代码去填一个本不该填的字段。
    @Test
    @SuppressWarnings("deprecation")
    void should_write_data_node_credentials_on_the_config_object() {
        Config config = RedissonClientFactory.buildConfig(StubConnectionDetails.standalone("redis.internal", 6379)
                .withUsername("svc-lock")
                .withPassword("s3cret"), properties());

        // 落在顶层才是被消费的那一份：一手实测 MasterSlaveConnectionManager 生成连接配置时读的是
        // Config.getUsername()，子配置的同名字段只是兜底。断言"子配置没被写"就是钉这条结论。
        assertThat(config.getUsername()).isEqualTo("svc-lock");
        assertThat(config.getPassword()).isEqualTo("s3cret");
        assertThat(config.useSingleServer().getUsername()).isNull();
        assertThat(config.useSingleServer().getPassword()).isNull();
    }

    @Test
    void should_skip_absent_or_blank_credentials_instead_of_authenticating_with_an_empty_string() {
        // "键写了却留空"在 Boot 的视图里是空串；把它交给 Redisson 等于声明"用空密码认证"。
        Config blank = RedissonClientFactory.buildConfig(StubConnectionDetails.standalone("redis", 6379)
                .withUsername("").withPassword(""), properties());
        assertThat(blank.getUsername()).isNull();
        assertThat(blank.getPassword()).isNull();

        Config absent = RedissonClientFactory.buildConfig(
                StubConnectionDetails.standalone("redis", 6379), properties());
        assertThat(absent.getUsername()).isNull();
        assertThat(absent.getPassword()).isNull();
    }

    @Test
    void should_switch_the_address_scheme_only_when_boot_handed_us_an_ssl_bundle() {
        Config plain = RedissonClientFactory.buildConfig(
                StubConnectionDetails.standalone("redis", 6379), properties());
        assertThat(plain.useSingleServer().getAddress()).isEqualTo("redis://redis:6379");

        Config tls = RedissonClientFactory.buildConfig(
                StubConnectionDetails.standalone("redis", 6379).withSslBundle(), properties());
        assertThat(tls.useSingleServer().getAddress()).isEqualTo("rediss://redis:6379");

        // 协议前缀对三种形态一视同仁：哨兵与集群节点同样可能是 TLS 的。
        Config tlsSentinel = RedissonClientFactory.buildConfig(StubConnectionDetails
                .sentinel("mymaster", StubConnectionDetails.nodes("sen", 26379), null, null, 0)
                .withSslBundle(), properties());
        assertThat(tlsSentinel.useSentinelServers().getSentinelAddresses()).containsExactly("rediss://sen:26379");

        Config tlsCluster = RedissonClientFactory.buildConfig(StubConnectionDetails.standalone("ignored", 6379)
                .withCluster(StubConnectionDetails.nodes("c", 6379))
                .withSslBundle(), properties());
        assertThat(tlsCluster.useClusterServers().getNodeAddresses()).containsExactly("rediss://c:6379");
    }

    @Test
    void should_map_sentinel_master_nodes_and_database() {
        Config config = RedissonClientFactory.buildConfig(StubConnectionDetails.sentinel("mymaster",
                StubConnectionDetails.nodes("sen-a", 26379, "sen-b", 26380), null, null, 3), properties());

        SentinelServersConfig servers = config.useSentinelServers();
        assertThat(config.isSentinelConfig()).isTrue();
        assertThat(servers.getMasterName()).isEqualTo("mymaster");
        assertThat(servers.getSentinelAddresses()).containsExactly("redis://sen-a:26379", "redis://sen-b:26380");
        assertThat(servers.getDatabase()).isEqualTo(3);
    }

    @Test
    void should_keep_sentinel_credentials_separate_from_data_node_credentials() {
        Config config = RedissonClientFactory.buildConfig(StubConnectionDetails.sentinel("mymaster",
                StubConnectionDetails.nodes("sen", 26379), "sen-user", "sen-pass", 0)
                .withUsername("data-user").withPassword("data-pass"), properties());

        SentinelServersConfig servers = config.useSentinelServers();
        // 两套凭据问的是两个对象：向哨兵问"主在哪"用 sentinel*，连上数据节点之后的 AUTH 用顶层那对。
        // 混用会让"只给哨兵配了密码"的部署在找主阶段就失败。
        assertThat(servers.getSentinelUsername()).isEqualTo("sen-user");
        assertThat(servers.getSentinelPassword()).isEqualTo("sen-pass");
        assertThat(config.getUsername()).isEqualTo("data-user");
        assertThat(config.getPassword()).isEqualTo("data-pass");
    }

    @Test
    void should_map_every_cluster_node() {
        Config config = RedissonClientFactory.buildConfig(StubConnectionDetails.standalone("ignored", 6379)
                .withCluster(List.of(new Node("n1", 6379), new Node("n2", 6380), new Node("n3", 6381))), properties());

        ClusterServersConfig cluster = config.useClusterServers();
        assertThat(config.isClusterConfig()).isTrue();
        assertThat(cluster.getNodeAddresses()).containsExactly("redis://n1:6379", "redis://n2:6380", "redis://n3:6381");
    }

    @Test
    void should_prefer_cluster_over_sentinel_and_sentinel_over_standalone() {
        // Boot 的键允许同时存在（写了 cluster.* 又留着 host），此时 Lettuce 侧按 cluster 生效。
        // 锁必须跟着同一个裁定，否则会出现"业务读写走 cluster、锁打在单机上"的组合——那等于没有互斥。
        Config clusterWins = RedissonClientFactory.buildConfig(StubConnectionDetails.standalone("single", 6379)
                .withSentinel("mymaster", StubConnectionDetails.nodes("sen", 26379))
                .withCluster(StubConnectionDetails.nodes("c", 6379)), properties());
        assertThat(clusterWins.isClusterConfig()).isTrue();

        Config sentinelWins = RedissonClientFactory.buildConfig(StubConnectionDetails.standalone("single", 6379)
                .withSentinel("mymaster", StubConnectionDetails.nodes("sen", 26379)), properties());
        assertThat(sentinelWins.isSentinelConfig()).isTrue();
    }

    @Test
    void should_refuse_master_replica_because_its_node_list_has_no_master_identity() {
        assertThatThrownBy(() -> RedissonClientFactory.buildConfig(
                StubConnectionDetails.masterReplica(StubConnectionDetails.nodes("a", 6379, "b", 6380)), properties()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("masterreplica")
                .hasMessageContaining("setMasterAddress")
                .hasMessageContaining("myddd.consistency.lock.enabled=false");
    }

    @Test
    void should_refuse_a_blank_sentinel_master() {
        for (String master : List.of("  ", "")) {
            assertThatThrownBy(() -> RedissonClientFactory.buildConfig(StubConnectionDetails.sentinel(master,
                    StubConnectionDetails.nodes("sen", 26379), null, null, 0), properties()))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("spring.data.redis.sentinel.master");
        }
        // null 走的是同一句话：哨兵形态没有 master 名就无从问出主在哪。
        assertThatThrownBy(() -> RedissonClientFactory.buildConfig(StubConnectionDetails.sentinel(null,
                StubConnectionDetails.nodes("sen", 26379), null, null, 0), properties()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("spring.data.redis.sentinel.master");
    }

    @Test
    void should_refuse_a_blank_standalone_host() {
        for (String host : List.of("   ", "")) {
            assertThatThrownBy(() -> RedissonClientFactory.buildConfig(
                    StubConnectionDetails.standalone(host, 6379), properties()))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("spring.data.redis.host");
        }
        assertThatThrownBy(() -> RedissonClientFactory.buildConfig(
                StubConnectionDetails.standalone(null, 6379), properties()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("spring.data.redis.host");
    }

    @Test
    void should_refuse_a_missing_or_empty_node_list_for_cluster_and_sentinel() {
        assertThatThrownBy(() -> RedissonClientFactory.buildConfig(
                StubConnectionDetails.standalone("r", 6379).withCluster(List.of()), properties()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("spring.data.redis.cluster.nodes");

        // 空列表是 Boot 真的会给出的输入（一手实测：PropertiesDataRedisConnectionDetails.asNodes 对 null
        // 属性返回 Collections.emptyList()）。null 则是接口本身没做的承诺——Cluster.getNodes() 是抽象方法，
        // 没有任何"不会返回 null"的约定，第三方实现给 null 时表现必须和给空列表一样：启动期一句能照着做的话。
        assertThatThrownBy(() -> RedissonClientFactory.buildConfig(
                StubConnectionDetails.standalone("r", 6379).withCluster(null), properties()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("spring.data.redis.cluster.nodes");

        assertThatThrownBy(() -> RedissonClientFactory.buildConfig(StubConnectionDetails.sentinel("mymaster",
                List.of(), null, null, 0), properties()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("spring.data.redis.sentinel.nodes");
    }

    @Test
    void should_refuse_a_details_view_that_offers_no_shape_at_all() {
        assertThatThrownBy(() -> RedissonClientFactory.buildConfig(new StubConnectionDetails(), properties()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("standalone")
                .hasMessageContaining("myddd.consistency.lock.enabled=false");
    }

    @Test
    void should_write_the_watchdog_cadence_from_the_property() {
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getLock().setWatchdogTimeout(Duration.ofSeconds(3));

        Config config = RedissonClientFactory.buildConfig(StubConnectionDetails.standalone("r", 6379), properties);

        assertThat(config.getLockWatchdogTimeout()).isEqualTo(3_000L);
    }

    @Test
    void should_keep_the_delivery_default_equal_to_redissons_own_default() {
        // 两边的缺省一旦漂开，业务"没配 watchdog"时的行为就会和 Redisson 文档不一致。
        // 这条断言取的是 Redisson 自己的缺省数字，不是我抄的一个常数。
        long redissonOwnDefault = new Config().getLockWatchdogTimeout();

        assertThat(properties().getLock().getWatchdogTimeout()).isEqualTo(Duration.ofMillis(redissonOwnDefault));
        assertThat(redissonOwnDefault).isEqualTo(30_000L);
    }

    /**
     * 建客户端会真的连（一手实测：{@code Config.isLazyInitialization()} 缺省 {@code false}，连不上约 7 秒后抛
     * {@code RedisConnectionException}）。这条是本模块唯一的慢用例，它同时钉住两件事：
     * {@code create} 走的是 eager 形态（有人改成 lazy 就会被抓住，而 lazy 会把启动期能发现的配置错误
     * 推迟到第一次加锁），以及失败消息里带着那个没人监听的主机端口。
     *
     * <p>用 {@code 127.0.0.1:1}：本机端口 1 上没有服务，connect 立刻被拒，不会像未路由的 IP 那样干等超时。
     * 这不是"连不上就跳过"的测试——它断言的是<b>失败本身</b>。
     */
    @Test
    void should_connect_eagerly_and_fail_fast_when_nothing_listens() {
        assertThatThrownBy(() -> RedissonClientFactory.create(
                StubConnectionDetails.standalone("127.0.0.1", 1), properties()))
                .isInstanceOf(org.redisson.client.RedisConnectionException.class)
                .hasMessageContaining("127.0.0.1")
                .hasMessageContaining("Unable to connect to Redis server");
    }
}
