package org.deep361.myddd.job.xxljob.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code myddd.job.*} 在 XXL-Job provider 侧的绑定。
 *
 * <p>本类与 {@code MydddJobSpringProperties} 绑<b>同一个前缀</b>却各自只读自己的子树：Job 没有装配壳
 * （R1），"总开关 + 选中标识"这两件事必须在每个 provider 里各落一次（§0.3-6），一致性由 Task 43 的
 * {@code EQ} 组钉行为、不由共享类型钉。两模块同时在 classpath 上时只会装配其中一个（Step 7 的条件），
 * 而"另一个 provider 的子树里的键"由 binder 默认忽略未知字段兜住——{@code IQ7} 用一次真绑定把这件事钉死，
 * 免得下一个人以为要给对方那一半也留字段。
 */
@ConfigurationProperties(prefix = "myddd.job")
public class MydddJobXxlJobProperties {

    /** 定时任务能力的总开关。 */
    private boolean enabled = true;

    /** 选中的 provider：{@code spring} 或 {@code xxljob}；无缺省值（缺省即歧义，见 {@link JobProviderSelection}）。 */
    private String provider;

    /** XXL-Job provider 自己的调参。 */
    private final Xxljob xxljob = new Xxljob();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Xxljob getXxljob() {
        return xxljob;
    }

    /**
     * 校验本 provider 用得上的三件事：执行器地址、执行器名、监听端口。
     *
     * @throws FrameworkException 三者之一不合期望形态
     */
    public void validate() {
        Xxljob section = getXxljob();
        if (section.getAdminAddresses() == null || section.getAdminAddresses().isBlank()) {
            throw new FrameworkException("myddd.job.xxljob.admin-addresses 必须配置（实际："
                    + section.getAdminAddresses() + "，期望形如 http://127.0.0.1:9099，多个用逗号分隔）。"
                    + "没有调度中心地址时执行器不会报错、只会静默不注册（§0.1 实测：adminAddresses 为空只打一条 info），"
                    + "那等于任务永远不跑");
        }
        if (section.getExecutorAppName() == null || section.getExecutorAppName().isBlank()) {
            throw new FrameworkException("myddd.job.xxljob.executor-app-name 必须非空白（实际："
                    + section.getExecutorAppName() + "，期望形如 myddd-executor）。调度中心按它寻址执行器，"
                    + "空白名会让作业配置里选不到本执行器");
        }
        int port = section.getExecutorPort();
        if (port < 1024 || port > 65535) {
            throw new FrameworkException("myddd.job.xxljob.executor-port 必须在 [1024, 65535]（实际：" + port
                    + "，期望形如 9999）。小于 1024 需要管理员权限；而 0 或负数会被执行器换成"
                    + "'从 9999 起找一个空闲端口'，那意味着调度中心记到的地址每次重启都可能变");
        }
    }

    /** {@code myddd.job.xxljob.*}。 */
    public static class Xxljob {

        private static final String DEFAULT_APP_NAME = "myddd-executor";
        private static final int DEFAULT_PORT = 9999;

        private String adminAddresses;
        private String executorAppName = DEFAULT_APP_NAME;
        private String accessToken;
        private int executorPort = DEFAULT_PORT;

        public String getAdminAddresses() {
            return adminAddresses;
        }

        public void setAdminAddresses(String adminAddresses) {
            this.adminAddresses = adminAddresses;
        }

        public String getExecutorAppName() {
            return executorAppName;
        }

        public void setExecutorAppName(String executorAppName) {
            this.executorAppName = executorAppName;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public int getExecutorPort() {
            return executorPort;
        }

        public void setExecutorPort(int executorPort) {
            this.executorPort = executorPort;
        }
    }
}
