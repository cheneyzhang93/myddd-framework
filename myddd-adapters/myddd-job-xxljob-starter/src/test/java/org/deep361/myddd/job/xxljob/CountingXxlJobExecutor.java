package org.deep361.myddd.job.xxljob;

import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 厂商执行器的计数替身：覆盖四个方法、只记不做。它是 {@code XS} 组唯一敢碰 {@code XxlJobExecutor} 的出路。
 *
 * <p>为什么不让真 {@code XxlJobExecutor} 跑 {@code start()}：那要绑端口、起两组线程、把 {@code this} 写进
 * 厂商的静态字段，而且失败形态（端口被占）取决于本机当时有什么在听；{@code XS} 组八条断的是<b>我们这一层</b>
 * 对厂商的调用次序与守卫。厂商自己跑得对不对由 Task 43 的 admin IT 给运行期证据。
 *
 * <p>四个覆盖点全部由 {@code javap} 逐字核对过可覆盖性（都是 {@code public}、无 {@code final}）：
 * {@code start() throws Exception}、{@code destroy()}、
 * {@code loadJobHandler(String)}、{@code registryJobHandler(String, IJobHandler)}。
 */
public class CountingXxlJobExecutor extends XxlJobExecutor {

    private final Map<String, IJobHandler> handlers = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final List<String> setters = new ArrayList<>();
    private Exception failureOnStart;
    private boolean handedOverStart;

    @Override
    public IJobHandler registryJobHandler(String jobName, IJobHandler jobHandler) {
        calls.add("registry:" + jobName);
        return handlers.put(jobName, jobHandler);
    }

    @Override
    public IJobHandler loadJobHandler(String jobName) {
        calls.add("load:" + jobName);
        return handlers.get(jobName);
    }

    @Override
    public void start() throws Exception {
        calls.add("start");
        // 厂商真身在这一步之后已经有了那些 helper 字段；替身只记这一个事实，不起线程、不绑端口。
        handedOverStart = true;
        if (failureOnStart != null) {
            throw failureOnStart;
        }
    }

    @Override
    public void destroy() {
        calls.add("destroy");
        handlers.clear();
        if (!handedOverStart) {
            // 厂商在从未 start() 的实例上调 destroy() 必 NPE（实测 S5-3/S5-4：triggerCallbackThreadHelper 为 null），
            // 而且 NPE 之前表已经清了。替身不把这一句照抄下来，XS3 就只是"断言替身没被调用"，
            // 而不是"我们的守卫挡住了一次真实的崩溃"。
            throw new NullPointerException("triggerCallbackThreadHelper");
        }
    }

    // 四个"我们该设"的键：记录并透传（XS8 要读 getPort()/getAppname()/getAccessToken() 确认值真进去了）。
    @Override
    public void setAdminAddresses(String adminAddresses) {
        setters.add("adminAddresses=" + adminAddresses);
        super.setAdminAddresses(adminAddresses);
    }

    @Override
    public void setAppname(String appname) {
        setters.add("appname=" + appname);
        super.setAppname(appname);
    }

    @Override
    public void setAccessToken(String accessToken) {
        setters.add("accessToken=" + accessToken);
        super.setAccessToken(accessToken);
    }

    @Override
    public void setPort(int port) {
        setters.add("port=" + port);
        super.setPort(port);
    }

    // 六个"我们不该碰"的键：只记录、不透传——被调用本身就是 XS8 要拒的事，不透传还能保证它们不留副作用。
    @Override
    public void setIp(String ip) {
        setters.add("ip=" + ip);
    }

    @Override
    public void setAddress(String address) {
        setters.add("address=" + address);
    }

    @Override
    public void setLogPath(String logPath) {
        setters.add("logPath=" + logPath);
    }

    @Override
    public void setTimeout(int timeout) {
        setters.add("timeout=" + timeout);
    }

    @Override
    public void setLogRetentionDays(int logRetentionDays) {
        setters.add("logRetentionDays=" + logRetentionDays);
    }

    @Override
    public void setEnabled(Boolean enabled) {
        setters.add("enabled=" + enabled);
    }

    /** setter 调用序列（{@code XS8} 的判据：恰好四条，且就是那四条）。 */
    public List<String> setters() {
        return List.copyOf(setters);
    }

    /** 调用序列，形如 {@code [registry:j1, load:j1, start, destroy]}。 */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    public int countOf(String prefix) {
        return (int) calls.stream().filter(each -> each.startsWith(prefix)).count();
    }

    /** 让下一次 {@code start()} 抛这个异常（{@code XS4}：启动失败之后停机仍要把 destroy 递下去）。 */
    public void failNextStartWith(Exception failure) {
        this.failureOnStart = failure;
    }
}
