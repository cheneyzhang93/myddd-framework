package org.deep361.myddd.auth.satoken.testing;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.context.model.SaResponse;
import cn.dev33.satoken.context.model.SaStorage;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpInterfaceDefaultImpl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sa-Token 侧的"当前线程有请求上下文"夹具：<b>零 Spring、零 Servlet、零 Redis</b>。
 *
 * <p>一支 {@link SaTokenContextForThreadLocalStaff#setModelBox} 就够了（探针 49d 一手：整条
 * {@code login → currentUser → hasPermission → logout → 重新 login} 全部走通，
 * {@code clearModelBox()} 之后再读回到抛 {@code SaTokenContextException} 的形态）。三支柱必须自己实现：
 * {@code SaRequest} 的抽象方法 <b>13</b> 个、{@code SaResponse} <b>5</b> 个、{@code SaStorage} <b>4</b> 个
 * （一手 {@code javap}，见本模块探针目录），全部是 {@code Map}/{@code List} 的薄壳。
 *
 * <p><b>不</b>用 {@code spring-test} 的 {@code MockHttpServletRequest}：那要把
 * {@code SaTokenContextForSpringInJakartaServlet} 注册进 {@code SaManager}，比三支假实现贵得多，
 * 还会把本模块的 8 条端口契约用例悄悄变成装配用例。
 *
 * <p><b>不做 token 回写</b>：{@code login} 之后同一个作用域里直接读身份即可成立（探针 49d 一手：
 * {@code after login currentUser=10001}，此时还没有把写出的 {@code Set-Cookie} 回填进请求头）。
 * 所以本夹具不假装客户端会回传 token——端口本来也不该依赖它（{@code AuthPort} 不返回 token）。
 *
 * <p><b>进程级静态状态的两条纪律</b>：① {@code SaManager} 的权限源是进程级静态，每个用例结束都要
 * {@link #restoreDefaultPermissionSource()}（否则下一个测试类拿到上一个类的假权限源，红在无关处）；
 * ② 会话与 token 存在进程级缺省内存 DAO 里且 {@code @AfterEach} 只清上下文不清会话，
 * 所以<b>每个用例用各自的 loginId 字面量</b>（本模块的 loginId 分配：{@code 49003}/{@code 49007}/
 * {@code 49004}……见各用例）。⚠️ 装权限源必须走 {@code SaManager.setStpInterface}：一手字节码
 * （{@code samgr.txt:169-191}）确认 {@code getStpInterface()} 只有"双检锁 + {@code new StpInterfaceDefaultImpl()}"
 * 一条懒装路径，<b>没有任何 ServiceLoader/SPI 兜底</b>，唯一写入口就是那个 setter（登记 D-188）。
 * 主代码禁碰 setter（{@code 49a} 第 1 条），夹具碰它是生产判据的另一半证据：本模块测试类路径上
 * 只有 {@code sa-token-core}、没有 starter ⇒ 没有 {@code SaBeanInject} ⇒ 不手动装就永远读到全 deny 的缺省实现。
 */
public final class SaTokenRequestScope {

    private final List<String> writtenHeaders = new ArrayList<>();

    /** 建立"本线程有请求上下文"的作用域（三支假的 request/response/storage 进 ThreadLocal 模型盒）。 */
    public void open() {
        SaTokenContextForThreadLocalStaff.setModelBox(new FakeRequest(), new FakeResponse(), new FakeStorage());
    }

    /** 拆掉上下文（幂等；探针 49d 证实拆完再读会抛，正是 {@code AUTH-C6} 与 {@code RS1}/{@code RS7} 需要的形状）。 */
    public void close() {
        SaTokenContextForThreadLocalStaff.clearModelBox();
        writtenHeaders.clear();
    }

    /** 本作用域内被写出的响应头，形如 {@code add:Set-Cookie=satoken=<uuid>; Max-Age=...}。 */
    public List<String> writtenHeaders() {
        return List.copyOf(writtenHeaders);
    }

    /** 直接装一个业务 {@code StpInterface}（{@code RS4} 要装"返回 null 的那一种写法"，只能用原始面）。 */
    public static void install(StpInterface source) {
        SaManager.setStpInterface(source);
    }

    /** 便捷绑法：按 loginId 给权限/角色；表里没有这个用户 ⇒ 空列表（不是 {@code null}）。 */
    public static void installByUser(Map<String, List<String>> permissionsByUser, Map<String, List<String>> rolesByUser) {
        SaManager.setStpInterface(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return permissionsByUser.getOrDefault(String.valueOf(loginId), List.of());
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return rolesByUser.getOrDefault(String.valueOf(loginId), List.of());
            }
        });
    }

    /** 还原厂商缺省权限源（两个方法都给空列表的那个 {@code StpInterfaceDefaultImpl}）。 */
    public static void restoreDefaultPermissionSource() {
        SaManager.setStpInterface(new StpInterfaceDefaultImpl());
    }

    private final class FakeRequest implements SaRequest {

        @Override
        public Object getSource() {
            return "myddd-fake-request";
        }

        @Override
        public String getParam(String name) {
            return null;
        }

        @Override
        public Collection<String> getParamNames() {
            return List.of();
        }

        @Override
        public Map<String, String> getParamMap() {
            return Map.of();
        }

        @Override
        public String getHeader(String name) {
            return null;
        }

        @Override
        public String getCookieValue(String name) {
            return null;
        }

        @Override
        public String getCookieFirstValue(String name) {
            return null;
        }

        @Override
        public String getCookieLastValue(String name) {
            return null;
        }

        @Override
        public String getRequestPath() {
            return "/myddd-it";
        }

        @Override
        public String getUrl() {
            return "http://localhost/myddd-it";
        }

        @Override
        public String getMethod() {
            return "GET";
        }

        @Override
        public String getHost() {
            return "localhost";
        }

        @Override
        public Object forward(String path) {
            return null;
        }
    }

    private final class FakeResponse implements SaResponse {

        @Override
        public Object getSource() {
            return "myddd-fake-response";
        }

        @Override
        public SaResponse setStatus(int sc) {
            return this;
        }

        @Override
        public SaResponse setHeader(String name, String value) {
            writtenHeaders.add("set:" + name + "=" + value);
            return this;
        }

        @Override
        public SaResponse addHeader(String name, String value) {
            writtenHeaders.add("add:" + name + "=" + value);
            return this;
        }

        @Override
        public Object redirect(String url) {
            return null;
        }
    }

    private final class FakeStorage implements SaStorage {

        private final Map<String, Object> data = new HashMap<>();

        @Override
        public Object getSource() {
            return "myddd-fake-storage";
        }

        @Override
        public Object get(String key) {
            return data.get(key);
        }

        @Override
        public SaStorage set(String key, Object value) {
            data.put(key, value);
            return this;
        }

        @Override
        public SaStorage delete(String key) {
            data.remove(key);
            return this;
        }
    }
}
