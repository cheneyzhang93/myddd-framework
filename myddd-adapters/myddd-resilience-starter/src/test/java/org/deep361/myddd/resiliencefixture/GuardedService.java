package org.deep361.myddd.resiliencefixture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.deep361.myddd.kernel.port.resilience.RateLimit;

/** 被 {@link RateLimit} 标注的业务替身。方法名即资源名的一部分（缺省名规则）。 */
public class GuardedService {

    /** 业务方法真正跑过的证据（RL12 要"放行就执行"，RL1/RL2 要"端口收到什么"）。 */
    public final List<String> ran = new ArrayList<>();

    @RateLimit
    public String defaultKey() {
        this.ran.add("defaultKey");
        return "done";
    }

    @RateLimit
    public String byArgument(String orderNo) {
        this.ran.add("byArgument:" + orderNo);
        return orderNo;
    }

    @RateLimit(permits = 5)
    public String fivePermits() {
        return "done";
    }

    @RateLimit(permits = -1)
    public String negativePermits() {
        return "unreachable";
    }

    @RateLimit(timeout = -1)
    public String negativeTimeout() {
        return "unreachable";
    }

    @RateLimit(timeout = 8)
    public String eightMillisBudget() {
        this.ran.add("eightMillisBudget");
        return "done";
    }

    @RateLimit(timeout = 1)
    public String oneMillisBudget() {
        return "done";
    }

    @RateLimit(timeout = 1000)
    public String oneSecondBudget() {
        return "done";
    }

    @RateLimit(timeout = 200)
    public String twoHundredMillisBudget() {
        return "done";
    }

    @RateLimit(timeout = 8, message = "系统繁忙，请稍后再试")
    public String refusedWithMessage() {
        return "unreachable";
    }

    @RateLimit(key = "#orderNo")
    public String spelKey(String orderNo) {
        return orderNo;
    }

    @RateLimit(key = "'spaced.key'")
    public String literalSpel() {
        return "done";
    }

    /** 受检异常原样传播（RL12 的第二半：调用方 {@code catch (IOException)} 必须抓得到）。 */
    @RateLimit
    public String throwsChecked() throws IOException {
        throw new IOException("业务自己的错");
    }
}
