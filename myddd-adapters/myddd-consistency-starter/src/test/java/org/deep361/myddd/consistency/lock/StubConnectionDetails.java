package org.deep361.myddd.consistency.lock;

import java.lang.reflect.Proxy;
import java.util.List;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.Cluster;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.MasterReplica;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.Node;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.Sentinel;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.Standalone;
import org.springframework.boot.ssl.SslBundle;

/**
 * {@link DataRedisConnectionDetails} 的测试替身：一份"Boot 已经解析完连接参数"的视图。
 *
 * <p><b>替身选这一层而不是属性绑定那一层</b>是有讲究的：{@code DataRedisConnectionDetails} 是 Boot 4.1.1 里
 * 连接参数的唯一出口（一手实测：接口 7 个方法、全部带 {@code default}，因此本类只覆盖用得上的那几个），
 * 锁客户端的形态完全由它决定。用真实绑定来造它会把 {@code DataRedisProperties} 的解析规则（单位换算、
 * 松散绑定、校验注解）混进我们的证据里，而那些是 Boot 的规矩，不该由本模块重钉（§0.3-5）。
 *
 * <p><b>不用 {@code Standalone.of(...)}</b>（一手实测：它内部
 * {@code Assert.hasLength(host, "'host' must not be empty")}，空白主机名在<b>造夹具</b>阶段就炸，
 * 压根到不了被测代码）。非法值必须由夹具表达得出来，否则"配了非法值"这一类用例没有证据。
 */
public final class StubConnectionDetails implements DataRedisConnectionDetails {

    private String username;
    private String password;
    private SslBundle sslBundle;
    private Standalone standalone;
    private Sentinel sentinel;
    private Cluster cluster;
    private MasterReplica masterReplica;

    /** 一条最小的 standalone 视图：主机 + 端口，库号取 {@code Standalone} 的接口缺省（0）。 */
    public static StubConnectionDetails standalone(String host, int port) {
        return new StubConnectionDetails().withStandalone(new StubStandalone(host, port, 0));
    }

    /** standalone 视图 + 显式库号。 */
    public static StubConnectionDetails standalone(String host, int port, int database) {
        return new StubConnectionDetails().withStandalone(new StubStandalone(host, port, database));
    }

    /** sentinel 视图，五要素全给（哨兵凭据与库号都可能单独缺席，所以不省略参数）。 */
    public static StubConnectionDetails sentinel(String master, List<Node> nodes, String sentinelUsername,
            String sentinelPassword, int database) {
        return new StubConnectionDetails().withSentinel(
                new StubSentinel(master, nodes, sentinelUsername, sentinelPassword, database));
    }

    /** 一份 master-replica 视图（本框架不支持，只用来演那一条快速失败）。 */
    public static StubConnectionDetails masterReplica(List<Node> nodes) {
        return new StubConnectionDetails().withMasterReplica(new StubMasterReplica(nodes));
    }

    public static List<Node> nodes(String host, int port) {
        return List.of(new Node(host, port));
    }

    public static List<Node> nodes(String firstHost, int firstPort, String secondHost, int secondPort) {
        return List.of(new Node(firstHost, firstPort), new Node(secondHost, secondPort));
    }

    public StubConnectionDetails withUsername(String username) {
        this.username = username;
        return this;
    }

    public StubConnectionDetails withPassword(String password) {
        this.password = password;
        return this;
    }

    /**
     * 塞一个 TLS 包。用 {@link Proxy} 而不是造真密钥库：本模块只读"有没有 bundle"这一个事实
     * （一手实测：{@code DataRedisConnectionDetails} 没有"ssl 开没开"的布尔开关），
     * 把 TLS 的复杂度搬进锁的测试换不来任何证据。
     */
    public StubConnectionDetails withSslBundle() {
        this.sslBundle = (SslBundle) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {SslBundle.class},
                (proxy, method, args) -> "name".equals(method.getName()) ? "stub" : null);
        return this;
    }

    public StubConnectionDetails withStandalone(Standalone standalone) {
        this.standalone = standalone;
        return this;
    }

    public StubConnectionDetails withSentinel(String master, List<Node> nodes) {
        this.sentinel = new StubSentinel(master, nodes, null, null, 0);
        return this;
    }

    public StubConnectionDetails withSentinel(Sentinel sentinel) {
        this.sentinel = sentinel;
        return this;
    }

    /**
     * cluster 形态的视图。节点列表<b>原样返回</b>（给 null 就是 null）：把 null 折叠成空列表会让
     * "视图压根没给出节点"这条输入永远测不到，而 {@code Cluster.getNodes()} 是抽象方法、接口没承诺过非空。
     * （Boot 自己的实现给的是空列表——一手实测 {@code asNodes} 对 null 返回 {@code Collections.emptyList()}，
     * 所以那一条由 {@code withCluster(List.of())} 演。）
     */
    public StubConnectionDetails withCluster(List<Node> nodes) {
        this.cluster = new StubCluster(nodes);
        return this;
    }

    public StubConnectionDetails withMasterReplica(MasterReplica masterReplica) {
        this.masterReplica = masterReplica;
        return this;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public SslBundle getSslBundle() {
        return sslBundle;
    }

    @Override
    public Standalone getStandalone() {
        return standalone;
    }

    @Override
    public Sentinel getSentinel() {
        return sentinel;
    }

    @Override
    public Cluster getCluster() {
        return cluster;
    }

    @Override
    public MasterReplica getMasterReplica() {
        return masterReplica;
    }

    /** standalone 形态。主机名允许是空白甚至 {@code null}："配了个空主机"正是被测分支之一。 */
    private record StubStandalone(String host, int port, int database) implements Standalone {

        @Override
        public String getHost() {
            return host;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public int getDatabase() {
            return database;
        }
    }

    /**
     * sentinel 形态。这里 {@code getUsername/getPassword} 读出来的是<b>哨兵自己</b>的凭据
     * （Boot 的接口分了两套：数据节点的凭据挂在 {@link DataRedisConnectionDetails} 顶层）。
     */
    private record StubSentinel(String master, List<Node> nodes, String sentinelUsername, String sentinelPassword,
                                int database) implements Sentinel {

        @Override
        public String getMaster() {
            return master;
        }

        @Override
        public List<Node> getNodes() {
            return nodes;
        }

        @Override
        public String getUsername() {
            return sentinelUsername;
        }

        @Override
        public String getPassword() {
            return sentinelPassword;
        }

        @Override
        public int getDatabase() {
            return database;
        }
    }

    private record StubCluster(List<Node> nodes) implements Cluster {

        @Override
        public List<Node> getNodes() {
            return nodes;
        }
    }

    private record StubMasterReplica(List<Node> nodes) implements MasterReplica {

        @Override
        public List<Node> getNodes() {
            return nodes;
        }
    }
}
