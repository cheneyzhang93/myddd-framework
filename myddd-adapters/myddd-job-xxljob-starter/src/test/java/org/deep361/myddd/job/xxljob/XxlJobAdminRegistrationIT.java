package org.deep361.myddd.job.xxljob;

import org.deep361.myddd.testing.container.MydddXxlJobAdminContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XXL-Job 执行器在<b>真 admin</b> 上的注册面验收（Task 42 交来的运行期证据）。
 *
 * <p><b>本类只做单元层做不了的那半件事</b>：{@code XS} 组七条证明了"我们把四个值递给厂商的四个 setter、
 * 停机只调一次 destroy"，但它证不了厂商拿这四个值<b>真的</b>在 admin 的注册表里落下一条能被读到的记录。
 * 那半件事只能问 admin 自己。
 *
 * <p><b>断言方向只有"向上写"</b>（{@code 32c} 的 javadoc 里 Task 43 的裁定）：宿主 JVM 里的执行器 → admin →
 * {@code xxl_job_registry} 表。"admin → 执行器"的回调方向<b>不在</b>判据面上：address 由厂商按
 * {@code "http://{ip_port}/"} 合成，容器网络里的宿主地址默认不可达，把它当判据等于把 IT 的红绿
 * 绑在一台机的 Docker 网络配置上（Task 57 若要跑真触发，再连同 {@code withExtraHost} 一起处理）。
 *
 * <p><b>无 Docker 时整类干净跳过</b>（§0.3-7）：{@code @Testcontainers(disabledWithoutDocker = true)} 走的是
 * {@code TestcontainersExtension} 实现的 {@code ExecutionCondition}（一手核对 {@code javap}：该类同时实现
 * {@code BeforeAllCallback} 与 {@code ExecutionCondition}），条件在 {@code @BeforeAll} <b>之前</b>判定，
 * 所以容器根本不会被 new 出来。这条决定了下面必须用 {@code @BeforeAll} 建容器而不是静态字段初始化——
 * 静态字段属于类初始化，条件判定与类初始化的先后不由我们控制。
 */
@Testcontainers(disabledWithoutDocker = true)
class XxlJobAdminRegistrationIT {

    /** 注册表分组：一手读数 {@code RegistTypeEnum} 只有 {@code EXECUTOR}、{@code ADMIN} 两个常量，执行器侧用前者的 {@code name()}。 */
    private static final String REGISTRY_GROUP = "EXECUTOR";
    /** 与 {@code 32c} 的容器约定同一个 appname（注册断言按它查 {@code registry_key}）。 */
    private static final String APP_NAME = MydddXxlJobAdminContainer.EXECUTOR_APP_NAME;
    /** 错口令那一支用独立 appname：它不能与正例在同一行上互相覆盖（{@code i_g_k_v} 是联合唯一键）。 */
    private static final String BAD_TOKEN_APP_NAME = APP_NAME + "-bad-token";

    /** 首跳是<b>立即</b>的（厂商 {@code CyclicThread} 先执行后睡，间隔 30000ms），所以预算只需要覆盖一次 HTTP 往返。 */
    private static final long REGISTRY_BUDGET_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 200L;
    /** 错口令那一支的等待窗口：跨过"首跳必然已经发生"这个点再断空，否则空表可能只是"还没跳"。 */
    private static final long REFUSAL_WINDOW_MILLIS = 3_000L;

    /** 合成地址的形状：{@code http://{ip}:{port}/}——端口由厂商从 9999 起挑，所以这里只断形状不断具体数字。 */
    private static final Pattern ADDRESS_SHAPE = Pattern.compile("^http://[^:/]+:\\d{4,5}/$");

    private static MydddXxlJobAdminContainer admin;

    private DefaultXxlJobRuntime runtime;

    @BeforeAll
    static void startAdmin() {
        admin = new MydddXxlJobAdminContainer();
    }

    @AfterAll
    static void stopAdmin() {
        if (admin != null) {
            admin.close();
        }
    }

    @AfterEach
    void shutdownRuntime() {
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    /** IT-J1：交出启动之后，admin 的注册表里在预算内出现这台执行器，且地址是厂商合成的那个形状。 */
    @Test
    void should_appear_in_the_admin_registry_once_started() {
        // 片段原文写成 new RuntimeFactory.started(admin)：RuntimeFactory 是只有静态工厂方法的
        // private 构造器类，new 一个它的静态成员方法在 Java 里不成立（编译错）。按 IT-J3 同一条写法去掉 new。
        runtime = RuntimeFactory.started(admin);
        List<String> addresses = awaitValues(REGISTRY_GROUP, APP_NAME, REGISTRY_BUDGET_MILLIS);
        assertFalse(addresses.isEmpty(),
                "预算 " + REGISTRY_BUDGET_MILLIS + "ms 内注册表里没有 " + APP_NAME + "：向上写这条路没走通");
        // 首跳只有一条记录：同一 appname+address 由联合唯一键 i_g_k_v 收拢，重复注册不该长出第二行。
        assertEquals(1, addresses.size(), "同一台执行器在注册表里有多行：" + addresses);
        assertTrue(ADDRESS_SHAPE.matcher(addresses.get(0)).matches(),
                "地址不是厂商合成的那个形状（期望 http://{ip}:{port}/）：" + addresses.get(0));
    }

    /** IT-J2：停机把注册行收回去——{@code registryRemove} 是无条件发的（字节码读数见 {@code 42a} 取证表）。 */
    @Test
    void should_leave_the_admin_registry_on_shutdown() {
        runtime = RuntimeFactory.started(admin);
        assertFalse(awaitValues(REGISTRY_GROUP, APP_NAME, REGISTRY_BUDGET_MILLIS).isEmpty(),
                "前置条件没成立：都没注册上，谈不上退订");
        runtime.shutdown();
        List<String> remaining = awaitUntilEmpty(REGISTRY_GROUP, APP_NAME, REGISTRY_BUDGET_MILLIS);
        assertTrue(remaining.isEmpty(), "停机后注册表里还剩：" + remaining);
    }

    /**
     * IT-J3：口令不对时 admin 不收——本地 {@code start()} 不许因此外抛（注册是后台线程的事），
     * 但注册表里必须始终没有这一行。正例（{@code IT-J1}）用的是同一条查询、同一张表，所以这条负例
     * 不是"表本来就是空的"那种假绿。
     */
    @Test
    void should_be_refused_by_the_admin_without_the_right_token() throws InterruptedException {
        runtime = RuntimeFactory.startedWith(admin, BAD_TOKEN_APP_NAME, "wrong-token");
        Thread.sleep(REFUSAL_WINDOW_MILLIS);
        List<String> rows = admin.registry().registryValues(REGISTRY_GROUP, BAD_TOKEN_APP_NAME);
        assertTrue(rows.isEmpty(), "错口令也注册上了，accessToken 校验是假的：" + rows);
        assertTrue(runtime.isStarted(), "注册被拒不等于启动没交出——isStarted() 的语义是后者（第 4 行裁定）");
    }

    /** 有界轮询到非空：返回那一刻读到的列表；超预算返回最后一次（可能为空），由用例断言去红。 */
    private List<String> awaitValues(String group, String key, long budgetMillis) {
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        List<String> values = List.of();
        while (System.nanoTime() < deadline) {
            values = admin.registry().registryValues(group, key);
            if (!values.isEmpty()) {
                return values;
            }
            sleepPollInterval();
        }
        return values;
    }

    /** 有界轮询到空：与上一条相反，超预算返回最后一次非空列表。 */
    private List<String> awaitUntilEmpty(String group, String key, long budgetMillis) {
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        List<String> values = admin.registry().registryValues(group, key);
        while (System.nanoTime() < deadline && !values.isEmpty()) {
            sleepPollInterval();
            values = admin.registry().registryValues(group, key);
        }
        return values;
    }

    private static void sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException polled) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("轮询被中断", polled);
        }
    }

    /** 建好并启动一台执行器：把"配置 → 启动"这两步绑成一个不可分割的动作，用例里不留半启动状态。 */
    private static final class RuntimeFactory {

        private RuntimeFactory() {
        }

        static DefaultXxlJobRuntime started(MydddXxlJobAdminContainer container) {
            return startedWith(container, APP_NAME, MydddXxlJobAdminContainer.ACCESS_TOKEN);
        }

        /**
         * 端口递 {@code 0}：这是<b>更低那道接缝</b>（{@code DefaultXxlJobRuntime} 的四参构造），不是配置入口——
         * 配置层的 1024 下界（{@code 42b} 第 4 条）针对的是"生产重启一次换一个地址"这个运维问题，
         * 而本类的判据恰好是"地址由厂商合成"，只断形状（{@code ADDRESS_SHAPE}）因此与自动端口兼容。
         * 固定端口会让同一台机上并发的别的构建抢不到端口，红在无关处。
         */
        static DefaultXxlJobRuntime startedWith(MydddXxlJobAdminContainer container, String appName,
                                                String accessToken) {
            DefaultXxlJobRuntime created = new DefaultXxlJobRuntime(
                    container.adminAddresses(), appName, accessToken, 0);
            created.start();
            return created;
        }
    }
}
